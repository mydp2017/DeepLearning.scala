package com.thoughtworks.deeplearning

import com.thoughtworks.deeplearning.Layer.{Aux, Tape}
import com.thoughtworks.deeplearning.Symbolic.Layers.Literal
import shapeless._

import scala.annotation.implicitNotFound
import scala.language.{existentials, implicitConversions}

/**
  * `@Symbolic`有三种用法：第一种用在方法参数上，第二种是用在方法返回值上，第三种是作为layer的类型
  *
  * @example{{{
  * def sumNetwork(implicit scores: INDArray @Symbolic): Double @Symbolic = {
  *   expScores.sum
  * }
  *
  * val predictor: (INDArray => Double) @Symbolic = sumNetwork
  * }}}
  *
  * 首先讨论第一种用法：当作为方法参数时，`INDArray @Symbolic`等价于`From[INDArray]##`@``,又等价于`Identity[INDArray,INDArray]` 其实它们都是`Layer.Aux[Tape.Aux[INDArray,INDArray],Tape.Aux[INDArray,INDArray]]`；
  * 第二种用法：当作为方法返回值时，`Double @Symbolic`等价于`To[Double]##`@``,等价于`(INDArray => Double) @Symbolic`,又等价于`Layer.Aux[Tape.Aux[INDArray,INDArray],Tape.Aux[Double,Double]]`,
  * 这里的Layer.Aux[]中的两个Tape.Aux[],第一个对应Input，里面包含Data和Delta，第二个对应Output，里面包含Data和Delta，详情参考[[Tape.Aux]]；
  * 第三种用法：作为Layer的类型: 这时`(INDArray => Double) @Symbolic`等价于`Layer.Aux[Tape.Aux[INDArray,INDArray],Tape.Aux[Double,Double]]`
  *
  */
@implicitNotFound("Don't know how to make ${NativeOutput} differentiable")
trait Symbolic[NativeOutput] {
  type `@`
}

private[deeplearning] trait LowPrioritySymbolic { this: Symbolic.type =>

  implicit def from[NativeOutput, Data0, Delta0](
      implicit toLiteral: Lazy[ToLiteral.Aux[NativeOutput, Data0, Delta0]]): From.Aux[NativeOutput, Data0, Delta0] =
    new From[NativeOutput] {
      type Data = Data0
      type Delta = Delta0
    }

}

object Symbolic extends LowPrioritySymbolic {

  trait ToLiteral[From] extends DepFn1[From] {

    type Data
    type Delta

    type Out = Literal[Data]

  }

  object ToLiteral {

    def fromData[From <: Data0, Data0, Delta0] = new ToLiteral[From] {
      override type Data = Data0
      override type Delta = Delta0

      override def apply(data: From) = Literal[Data](data)
    }

    type Aux[From, Data0, Delta0] = ToLiteral[From] {
      type Data = Data0
      type Delta = Delta0
    }

  }

  object Layers {

    final case class Identity[Data0, Delta0]() extends Layer {

      type Data = Data0
      type Delta = Delta0

      type Input = Tape.Aux[Data, Delta]
      type Output = Tape.Aux[Data, Delta]

      override def forward(input: Input): Output = {
        input.addReference()
      }

      private type ConcreteTape = Tape.Aux[Data, Delta]

      // Workaround for https://issues.scala-lang.org/browse/SI-10008
      type Tape >: ConcreteTape <: ConcreteTape

      private[deeplearning] type To[OutputPlaceholder <: Identity[_, _]] = Layer.Aux[Tape, OutputPlaceholder#Tape]

    }

    object Identity {

      implicit def implicitlyApply[Data, Delta]: Identity[Data, Delta] = new Identity

      private[deeplearning] type DataOf[`@` <: Identity[_, _]] = t.Data forSome { val t: `@` }
      private[deeplearning] type DeltaOf[`@` <: Identity[_, _]] = t.Delta forSome { val t: `@` }

      implicit def inputPlaceholderToLayer[InputData, InputDelta]
        : ToLayer.Aux[Identity[InputData, InputDelta], Tape.Aux[InputData, InputDelta], InputData, InputDelta] =
        new ToLayer[Identity[InputData, InputDelta], Tape.Aux[InputData, InputDelta]] {
          override type OutputData = InputData
          override type OutputDelta = InputDelta

          override def apply(input: Identity[InputData, InputDelta]) =
            Identity[InputData, InputDelta]()
        }

    }

    final case class Literal[Data0](value0: Data0) extends Layer with Tape {
      override type Data = Data0
      override type Delta = Any
      override type Input = Tape
      override type Output = Tape.Aux[Data, Delta]

      override def value: Data = value0

      override def forward(input: Input) = this

      override def isTrainable: Boolean = false

      override protected def forceBackward(delta: Delta): Unit = {}

      override def close(): Unit = {}

      override def addReference() = this
    }

  }

  import Layers._

  private[deeplearning] trait IsLayer {
    type OutputData
    type OutputDelta
    type InputData
    type InputDelta
    type ConcreteLayer = Layer.Aux[Tape.Aux[InputData, InputDelta], Tape.Aux[OutputData, OutputDelta]]
    type `@` >: ConcreteLayer <: ConcreteLayer
  }

  private[deeplearning] object IsLayer {

    type Aux[InputData0, InputDelta0, OutputData0, OutputDelta0] = IsLayer {
      type OutputData = OutputData0
      type OutputDelta = OutputDelta0
      type InputData = InputData0
      type InputDelta = InputDelta0
    }

  }

  private[deeplearning] trait To[NativeOutput] extends Symbolic[NativeOutput] with IsLayer

  private[deeplearning] object To {
    type Aux[NativeOutput, InputData0, InputDelta0, OutputData0, OutputDelta0] = To[NativeOutput] {
      type OutputData = OutputData0
      type OutputDelta = OutputDelta0
      type InputData = InputData0
      type InputDelta = InputDelta0
    }

    def apply[NativeOutput](implicit tc: To[NativeOutput]): tc.type = tc
  }

  implicit def to[NativeOutput, InputData0, InputDelta0, OutputData0, OutputDelta0](
      implicit inputPlaceHolder: Identity[InputData0, InputDelta0],
      toLiteral: ToLiteral.Aux[NativeOutput, OutputData0, OutputDelta0]
  ): To.Aux[NativeOutput, InputData0, InputDelta0, OutputData0, OutputDelta0] =
    new To[NativeOutput] {
      type OutputData = OutputData0
      type OutputDelta = OutputDelta0
      type InputData = InputData0
      type InputDelta = InputDelta0
    }

  private[deeplearning] trait FromTo[NativeInput, NativeOutput]
      extends Symbolic[NativeInput => NativeOutput]
      with IsLayer

  private[deeplearning] object FromTo {

    /** @template */
    type Aux[NativeInput, NativeOutput, InputData0, InputDelta0, OutputData0, OutputDelta0] =
      FromTo[NativeInput, NativeOutput] {
        type InputData = InputData0
        type InputDelta = InputDelta0
        type OutputData = OutputData0
        type OutputDelta = OutputDelta0
      }

    def apply[NativeInput, NativeOutput](implicit typeClass: FromTo[NativeInput, NativeOutput]): typeClass.type =
      typeClass

  }

  implicit def fromTo[NativeInput, NativeOutput, InputData0, InputDelta0, OutputData0, OutputDelta0](
      implicit inputToLiteral: Lazy[ToLiteral.Aux[NativeInput, InputData0, InputDelta0]],
      outputToLiteral: Lazy[ToLiteral.Aux[NativeOutput, OutputData0, OutputDelta0]])
    : FromTo.Aux[NativeInput, NativeOutput, InputData0, InputDelta0, OutputData0, OutputDelta0] =
    new FromTo[NativeInput, NativeOutput] {
      type InputData = InputData0
      type InputDelta = InputDelta0
      type OutputData = OutputData0
      type OutputDelta = OutputDelta0
    }

  private[deeplearning] type Placeholder[Data, Delta] = Identity[Data, Delta]

  private[deeplearning] val Placeholder = Identity

  implicit final class ToLayerOps[From, Input <: Tape, OutputData, OutputDelta](from: From)(
      implicit typeClassInstance: ToLayer.Aux[From, Input, OutputData, OutputDelta]
  ) {

    def toLayer: Layer.Aux[Input, Tape.Aux[OutputData, OutputDelta]] = typeClassInstance(from)

  }

  implicit final class ToTapeOps[From, Data, Delta](from: From)(
      implicit lift: ToLiteral.Aux[From, Data, Delta]
  ) {

    @inline
    def toTape: Tape.Aux[Data, Delta] = lift(from)

  }

  implicit def autoToLayer[A, Input <: Tape, OutputData, OutputDelta](a: A)(
      implicit toLayer: ToLayer.Aux[A, Input, OutputData, OutputDelta])
    : Layer.Aux[Input, Tape.Aux[OutputData, OutputDelta]] = {
    toLayer(a)
  }

  private[deeplearning] sealed trait ToLayerLowPriorityImplicits { this: ToLayer.type =>

    implicit def toLayerOfPlaceholder[Input0 <: Tape, OutputPlaceholder <: Identity[_, _]]
      : ToLayer.OfPlaceholder[Layer.Aux[Input0, OutputPlaceholder#Tape], Input0, OutputPlaceholder] = {
      ToLayer
        .layerToLayer[Input0, Placeholder.DataOf[OutputPlaceholder], Placeholder.DeltaOf[OutputPlaceholder]]
        .asInstanceOf[ToLayer.OfPlaceholder[Layer.Aux[Input0, OutputPlaceholder#Tape], Input0, OutputPlaceholder]]
    }

    implicit def isLayerToLayer[NativeInput, NativeOutput, InputData0, InputDelta0, OutputData0, OutputDelta0]
      : ToLayer.Aux[
        IsLayer.Aux[InputData0, InputDelta0, OutputData0, OutputDelta0]#`@`,
        Tape.Aux[InputData0, InputDelta0],
        OutputData0,
        OutputDelta0
      ] = {
      layerToLayer
    }

  }

  object ToLayer extends ToLayerLowPriorityImplicits {

    type Aux[From, Input <: Tape, OutputData0, OutputDelta0] = ToLayer[From, Input] {
      type OutputData = OutputData0
      type OutputDelta = OutputDelta0
    }

    type OfPlaceholder[From, Input <: Tape, OutputPlaceholder <: Identity[_, _]] =
      ToLayer.Aux[From, Input, differentiablePlaceholder.Data, differentiablePlaceholder.Delta] forSome {
        val differentiablePlaceholder: OutputPlaceholder
      }

    implicit def layerToLayer[Input <: Tape, OutputData0, OutputDelta0]
      : ToLayer.Aux[Layer.Aux[Input, Tape.Aux[OutputData0, OutputDelta0]], Input, OutputData0, OutputDelta0] =
      new ToLayer[Layer.Aux[Input, Tape.Aux[OutputData0, OutputDelta0]], Input] {
        override type OutputData = OutputData0
        override type OutputDelta = OutputDelta0

        override def apply(layer: Layer.Aux[Input, Tape.Aux[OutputData, OutputDelta]]) = layer
      }

    implicit def placeholderToLayer[From, InputData, InputDelta, OutputData0, OutputDelta0](
        implicit inputPlaceholder: Identity[InputData, InputDelta],
        toLiteral: ToLiteral.Aux[From, OutputData0, OutputDelta0])
      : ToLayer.Aux[From, Tape.Aux[InputData, InputDelta], OutputData0, OutputDelta0] = {
      new ToLayer[From, Tape.Aux[InputData, InputDelta]] {
        override type OutputData = OutputData0
        override type OutputDelta = OutputDelta0

        override def apply(from: From) = toLiteral(from)
      }
    }

  }

  /**
    * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
    */
  @implicitNotFound("Cannot convert ${From} to layer")
  trait ToLayer[From, Input <: Tape] extends DepFn1[From] {
    type OutputData
    type OutputDelta
    type Output = Tape.Aux[OutputData, OutputDelta]
    type Out = Layer.Aux[Input, Output]
  }

  private[deeplearning] trait From[NativeOutput] extends Symbolic[NativeOutput] with DepFn0 {

    type Data
    type Delta

    type `@` = Identity[Data, Delta]

    type Out = `@`

    override def apply() = new Identity

  }

  private[deeplearning] object From {

    /** @template */
    type Aux[NativeOutput, Data0, Delta0] = From[NativeOutput] {
      type Data = Data0
      type Delta = Delta0
    }

    def apply[NativeOutput](implicit typeClass: From[NativeOutput]): typeClass.type = typeClass

  }

}