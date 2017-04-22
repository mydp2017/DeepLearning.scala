package com.thoughtworks.deeplearning

import com.thoughtworks.deeplearning.DifferentiableKernel.Zero.FloatZero
import com.thoughtworks.deeplearning.Memory.Address
import com.thoughtworks.deeplearning.OpenCL.CommandQueue.GlobalWorkSizeOnlyDimension
import com.thoughtworks.deeplearning.OpenCL.{CommandQueue, Device, Kernel}
import com.thoughtworks.deeplearning.OpenCLCodeGenerator.DslType.{DslBuffer, DslDouble, DslFloat, DslInt}
import com.thoughtworks.deeplearning.OpenCLCodeGenerator._
import com.thoughtworks.each.Monadic._
import com.thoughtworks.raii.RAIITask
import jdk.nashorn.internal.objects.annotations.Setter
import shapeless.labelled._
import shapeless._

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import scalaz.{@@, Monad, Monoid}
import scalaz.Tags.{Multiplication, Parallel}
import scalaz.concurrent.Future
import scalaz.concurrent.Future.{ParallelFuture, futureParallelApplicativeInstance}
import scalaz.std.anyVal._
import scalaz.std.iterable._
import scalaz.syntax.foldable._
import scala.language.higherKinds

object DifferentiableKernel {

  private[DifferentiableKernel] trait StaticDslTypeExtractor {
    type AbstractType[A] <: DslType

    implicit def dslDouble: AbstractType[Double]
    implicit def dslFloat: AbstractType[Float]
    implicit def dslInt: AbstractType[Int]
    implicit def dslBuffer[Element: AbstractType]: AbstractType[OpenCL.Buffer[Element]]
  }

  private[DifferentiableKernel] trait StaticDslExpressionExtractor {
    type AbstractType[A] <: DslExpression

    def apply[A](expression: DslExpression): AbstractType[A]
  }

  @inline val StaticDslExpression: StaticDslExpressionExtractor =
    new StaticDslExpressionExtractor {
      override type AbstractType[A] = DslExpression

      override def apply[A](expression: DslExpression): DslExpression = expression
    }

  @inline val StaticDslType: StaticDslTypeExtractor =
    new StaticDslTypeExtractor {
      @inline
      override final def dslDouble = DslDouble

      @inline
      override final def dslFloat = DslFloat

      @inline
      override final def dslInt = DslInt

      override type AbstractType[A] = DslType

      override def dslBuffer[Element](implicit elementType: DslType): DslType = DslBuffer(elementType)
    }
  // TODO: https://github.com/ClaireNeveu/macrame/issues/7
  type StaticDslType[A] = StaticDslType.AbstractType[A]
  type StaticDslExpression[A] = StaticDslExpression.AbstractType[A]

  import StaticDslType._

  final case class OpenCLLayer[OutputElementData, OutputElementDelta, LocalDelta <: HList](
      data: StaticDslExpression[OutputElementData],
      jacobian: LocalDelta) {

    import OpenCLLayer._

    def compile(context: OpenCL.Context, device: Device, commandQueue: CommandQueue)(
        implicit compiler: Compiler[OutputElementData, OutputElementDelta, LocalDelta],
        outputDataMemory: Memory[OutputElementData],
        outputDeltaMemory: Memory[OutputElementDelta],
        outputDataType: StaticDslType[OutputElementData],
        outputDeltaType: StaticDslType[OutputElementDelta],
        executor: ExecutionContext): RAIITask[(Int, compiler.ParameterRecord) => RAIITask[
      Tape.Aux[OpenCL.Buffer[OutputElementData], OpenCL.Buffer[OutputElementDelta]]]] = throwableMonadic[RAIITask] {

      RAIITask.jump().each

      def forwardKernelDefinition: KernelDefinition = {
        val outputIndex = {
          // TODO: Support n-dimension Array
          DslExpression.GetGlobalId(DslExpression.IntLiteral(0))
        }
        val effect = DslEffect.Update(DslExpression.Identifier(OutputId), outputIndex, data, outputDataType)
        KernelDefinition(ForwardKernelName, compiler.forwardParameters, Seq(effect))
      }

      val forwardSource = OpenCLCodeGenerator.generateSourceCode(forwardKernelDefinition).toArray[CharSequence]
      val forwordProgram = RAIITask.managed(context.createProgramWithSource(forwardSource)).each
      RAIITask.unmanaged(forwordProgram.build()).each
      val forwardKernelTask = RAIITask.managed(forwordProgram.createKernel(ForwardKernelName))

      { (expectedSize: Int, inputParameterMap: compiler.ParameterRecord) =>
        throwableMonadic[RAIITask] {
          val kernel = forwardKernelTask.each
          val outputBuffer =
            RAIITask.managed(context.createBuffer[OutputElementData](expectedSize)(outputDataMemory)).each
          compiler.setKernelInputArguments(kernel, 1, inputParameterMap)
          kernel.setArg(0, outputBuffer)
          val event =
            RAIITask
              .managed(
                commandQueue.enqueueNDRangeKernel(kernel, Seq(GlobalWorkSizeOnlyDimension(Address(expectedSize)))))
              .each
          RAIITask.unmanaged(event.waitForComplete()).each
          new Tape {
            override def data: OpenCL.Buffer[OutputElementData] = outputBuffer

            override def backward[OutputDeltaBuffer <: OpenCL.Buffer[OutputElementDelta]](
                outputDeltaTask: RAIITask[OutputDeltaBuffer]): Future[Unit] = {
              Future.suspend {
                Future.now(()) // TODO: backward
              }

            }

            // TODO: Change OutputData and OutputDelta to a pair of OpenCL.Buffer and OpenCL.Event
            override type Data = OpenCL.Buffer[OutputElementData]
            override type Delta = OpenCL.Buffer[OutputElementDelta]
          }: Tape.Aux[OpenCL.Buffer[OutputElementData], OpenCL.Buffer[OutputElementDelta]]
        }
      }
    }
  }

  trait GetElement[Operand0, Operand1] extends DepFn2[Operand0, Operand1]
  object GetElement {
    type Aux[Operand0, Operand1, Out0] = GetElement[Operand0, Operand1] {
      type Out = Out0
    }

    implicit def bufferJacobianGetElement[Data, Delta]
      : GetElement.Aux[BufferJacobian[Data, Delta], StaticDslExpression[Int], StaticDslExpression[Delta]] = ???

    implicit def hconsGetElement[Key, Head, Tail <: HList, Operand1, HeadOut, TailOut <: HList](
        implicit headGetElement: GetElement.Aux[Head, Operand1, HeadOut],
        tailGetElement: GetElement.Aux[Tail, Operand1, TailOut])
      : GetElement.Aux[FieldType[Key, Head] :: Tail, Operand1, FieldType[Key, HeadOut] :: TailOut] =
      new GetElement[FieldType[Key, Head] :: Tail, Operand1] {
        override type Out = FieldType[Key, HeadOut] :: TailOut

        override def apply(operand0: FieldType[Key, Head] :: Tail, operand1: Operand1): Out = {
          field[Key](headGetElement(operand0.head, operand1)) :: tailGetElement(operand0.tail, operand1)
        }
      }

    implicit def hnilGetElement[Operand1]: GetElement.Aux[HNil, Operand1, HNil] = new GetElement[HNil, Operand1] {
      override type Out = HNil

      override def apply(operand0: HNil, operand1: Operand1): HNil.type = HNil
    }
  }
  trait Plus[Operand0, Operand1] extends DepFn2[Operand0, Operand1]
  object Plus {
    type Aux[Operand0, Operand1, Out0] = Plus[Operand0, Operand1] {
      type Out = Out0
    }
    implicit def floatPlus = new Plus[StaticDslExpression[Float], StaticDslExpression[Float]] {
      override type Out = StaticDslExpression[Float]
      override def apply(operand0: StaticDslExpression[Float], operand1: StaticDslExpression[Float]): Out = {
        StaticDslExpression[Float](DslExpression.Plus(operand0, operand1, DslType.DslFloat))
      }
    }

    implicit def pickFieldExistsInOperand0Only[K, V, T <: HList, M <: HList, Out0 <: HList](
        implicit mt: Plus.Aux[T, M, Out0],
        lacksKey: shapeless.ops.record.LacksKey[M, K]): Aux[FieldType[K, V] :: T, M, FieldType[K, V] :: Out0] =
      new Plus[FieldType[K, V] :: T, M] {
        type Out = FieldType[K, V] :: Out0
        def apply(l: FieldType[K, V] :: T, m: M): Out = l.head :: mt(l.tail, m)
      }
    implicit def mergeFieldExistsBoth[K, V0, V1, V, T <: HList, M <: HList, MT <: HList, Out0 <: HList](
        implicit rm: shapeless.ops.record.Remover.Aux[M, K, (V1, MT)],
        mt: Plus.Aux[T, MT, Out0],
        callback: Plus.Aux[V0, V1, V]): Aux[FieldType[K, V0] :: T, M, FieldType[K, V] :: Out0] = {
      new Plus[FieldType[K, V0] :: T, M] {
        type Out = FieldType[K, V] :: Out0
        def apply(l: FieldType[K, V0] :: T, m: M): Out = {
          val (mv, mr) = rm(m)
          val up = field[K](callback(l.head: V0, mv))
          up :: mt(l.tail, mr)
        }
      }
    }

    implicit def mergeNil[M <: HList]: Aux[HNil, M, M] = {
      new Plus[HNil, M] {
        type Out = M
        def apply(l: HNil, m: M): Out = m
      }
    }
  }
  trait Times[Operand0, Operand1] extends DepFn2[Operand0, Operand1]
  abstract class LowPriorityTimes0 {
    implicit def swap[Operand0, Operand1, Out0](
        implicit times: Times.Aux[Operand1, Operand0, Out0]): Times.Aux[Operand0, Operand1, Out0] =
      new Times[Operand0, Operand1] {
        override type Out = Out0

        override def apply(operand0: Operand0, operand1: Operand1): Out = times(operand1, operand0)
      }
  }
  object Times extends LowPriorityTimes0 {
    type Aux[Operand0, Operand1, Out0] = Times[Operand0, Operand1] {
      type Out = Out0
    }

    implicit def floatTimes = new Times[StaticDslExpression[Float], StaticDslExpression[Float]] {
      override type Out = StaticDslExpression[Float]
      override def apply(operand0: StaticDslExpression[Float], operand1: StaticDslExpression[Float]): Out = {
        StaticDslExpression(DslExpression.Times(operand0, operand1, DslType.DslFloat))
      }
    }

    implicit def hconsTimes[Key, Head, Tail <: HList, Operand1, HeadOut, TailOut <: HList](
        implicit headTimes: Times.Aux[Head, Operand1, HeadOut],
        tailTimes: Times.Aux[Tail, Operand1, TailOut])
      : Times.Aux[FieldType[Key, Head] :: Tail, Operand1, FieldType[Key, HeadOut] :: TailOut] =
      new Times[FieldType[Key, Head] :: Tail, Operand1] {
        override type Out = FieldType[Key, HeadOut] :: TailOut

        override def apply(operand0: FieldType[Key, Head] :: Tail, operand1: Operand1): Out = {
          field[Key](headTimes(operand0.head, operand1)) :: tailTimes(operand0.tail, operand1)
        }
      }

    implicit def hnilTimes[Operand1]: Times.Aux[HNil, Operand1, HNil] = new Times[HNil, Operand1] {
      override type Out = HNil

      override def apply(operand0: HNil, operand1: Operand1): HNil.type = HNil
    }
  }
  trait Zero extends DepFn0
  object Zero {
    type Aux[Out0] = Zero {
      type Out = Out0
    }

    implicit object FloatZero extends Zero {
      type Out = StaticDslExpression[Float]
      override def apply(): Out = StaticDslExpression(DslExpression.FloatLiteral(0.0f))
    }
  }
  trait One extends DepFn0
  object One {
    type Aux[Out0] = One {
      type Out = Out0
    }
  }

  object OpenCLLayer {
    private[deeplearning] final val OutputId = new AnyRef
    @inline private[deeplearning] final val ForwardKernelName = "forward"
    @inline private[deeplearning] final def backwardKernelName(index: Int) = raw"""backward_$index"""

    def floatLiteral(data: Float): OpenCLLayer[Float, Float, HNil] = {
      OpenCLLayer[Float, Float, HNil](StaticDslExpression[Float](DslExpression.FloatLiteral(data)), HNil)
    }
    def intLiteral(data: Int): OpenCLLayer[Int, Float, HNil] = {
      OpenCLLayer[Int, Float, HNil](StaticDslExpression[Int](DslExpression.IntLiteral(data)), HNil)
    }

    def bufferIdentifier[Data, Delta](
        implicit key: Witness): OpenCLLayer[OpenCL.Buffer[Data],
                                            OpenCL.Buffer[Delta],
                                            FieldType[key.T, BufferJacobian[Data, Delta]] :: HNil] = {
      OpenCLLayer[OpenCL.Buffer[Data], OpenCL.Buffer[Delta], FieldType[key.T, BufferJacobian[Data, Delta]] :: HNil](
        StaticDslExpression(DslExpression.Identifier(key.value)),
        field[key.T](BufferJacobian.One[Data, Delta]()) :: HNil
      )
    }

    def getGlobalId[LocalDelta <: HList](
        dimension: OpenCLLayer[Int, Float, LocalDelta]): OpenCLLayer[Int, Float, LocalDelta] = {
      ???
    }

    def getElement[ElementData,
                   ElementDelta,
                   BufferLocalDelta <: HList,
                   IndexLocalDelta <: HList,
                   ElementLocalDelta <: HList,
                   ZeroIndexLocalDelta <: HList,
                   LocalDelta <: HList](
        buffer: OpenCLLayer[OpenCL.Buffer[ElementData], OpenCL.Buffer[ElementDelta], BufferLocalDelta],
        index: OpenCLLayer[Int, Float, IndexLocalDelta])(
        implicit elementDataType: StaticDslType[ElementData],
        zero: Zero.Aux[StaticDslExpression[ElementDelta]],
        timesZero: Times.Aux[IndexLocalDelta, StaticDslExpression[ElementDelta], ZeroIndexLocalDelta],
        getDeltaElement: GetElement.Aux[BufferLocalDelta, StaticDslExpression[Int], ElementLocalDelta],
        plus: Plus.Aux[ElementLocalDelta, ZeroIndexLocalDelta, LocalDelta]
    ): OpenCLLayer[ElementData, ElementDelta, LocalDelta] = {
      OpenCLLayer[ElementData, ElementDelta, LocalDelta](
        StaticDslExpression[ElementData](DslExpression.GetElement(buffer.data, index.data, elementDataType)),
        plus(getDeltaElement(buffer.jacobian, index.data), timesZero(index.jacobian, zero()))
      )
    }

  }

  import OpenCLLayer._

  sealed trait BufferJacobian[Data, Delta] //(index: StaticDslExpression[Int], value: StaticDslExpression[Delta])
  object BufferJacobian {
    final case class One[Data, Delta]() extends BufferJacobian[Data, Delta]
  }

  trait InputCompiler[Key, LocalDelta] {
    type Input <: Tape

    def forwardParameter: Parameter

    def setArgument(kernel: Kernel, startIndex: Int, input: Input): Unit
  }

  object InputCompiler {

    type Aux[Key, LocalDelta, Input0] = InputCompiler[Key, LocalDelta] {
      type Input = Input0
    }

//    implicit def bufferInputCompiler[Key, ]

  }

  trait Compiler[OutputElementData, OutputElementDelta, LocalDelta <: HList] {
    type ParameterRecord <: HList

    def forwardInputParameters: List[Parameter]

    def forwardParameters(implicit outputDataType: StaticDslType[OutputElementData]): List[Parameter] =
      Parameter(OutputId, DslType.DslBuffer(outputDataType)) :: forwardInputParameters

    def setKernelInputArguments(kernel: Kernel, startIndex: Int, parameters: ParameterRecord)
  }

  object Compiler {
    type Aux[OutputElementData, OutputElementDelta, LocalDelta <: HList, ParameterRecord0] =
      Compiler[OutputElementData, OutputElementDelta, LocalDelta] {
        type ParameterRecord = ParameterRecord0
      }

    implicit def hnilFill[OutputElementData, OutputElementDelta]
      : Compiler.Aux[OutputElementData, OutputElementDelta, HNil, HNil] =
      new Compiler[OutputElementData, OutputElementDelta, HNil] {
        override type ParameterRecord = HNil

        override def forwardInputParameters: Nil.type = Nil

        override def setKernelInputArguments(kernel: Kernel, startIndex: Int, parameters: HNil): Unit = {}
      }

    implicit def hconsFill[OutputElementData,
                           OutputElementDelta0,
                           Key,
                           LocalDeltaHead,
                           LocalDeltaTail <: HList,
                           Input,
                           TailParameterRecord <: HList](
        implicit headInputCompiler: InputCompiler.Aux[Key, LocalDeltaHead, Input],
        tailCompiler: Compiler.Aux[OutputElementData, OutputElementDelta0, LocalDeltaTail, TailParameterRecord])
      : Compiler.Aux[OutputElementData,
                     OutputElementDelta0,
                     FieldType[Key, LocalDeltaHead] :: LocalDeltaTail,
                     FieldType[Key, Input] :: TailParameterRecord] =
      new Compiler[OutputElementData, OutputElementDelta0, FieldType[Key, LocalDeltaHead] :: LocalDeltaTail] {
        override type ParameterRecord = FieldType[Key, Input] :: TailParameterRecord
        override def forwardInputParameters: List[Parameter] =
          headInputCompiler.forwardParameter :: tailCompiler.forwardInputParameters

        override def setKernelInputArguments(kernel: Kernel,
                                             startIndex: Int,
                                             parameters: FieldType[Key, Input] :: TailParameterRecord): Unit = {
          headInputCompiler.setArgument(kernel, startIndex, parameters.head)
          tailCompiler.setKernelInputArguments(kernel, startIndex + 1, parameters.tail)
        }
      }
  }

}
