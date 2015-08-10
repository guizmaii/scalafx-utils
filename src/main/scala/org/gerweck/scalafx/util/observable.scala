package org.gerweck.scalafx.util

import language.implicitConversions

import scalaz._

import scalafx.beans.property._
import scalafx.beans.value._

trait ObservableImplicits {
  implicit val observableInstances = new Applicative[Observable] with Functor[Observable] with Monad[Observable] {
    /* Map can be derived from `ap`, but this adds less overhead. */
    override def map[A, B](a: Observable[A])(f: A => B): ObservableValue[B, B] = {
      @inline def recalculate(): B = f(a.value)

      val originalValue = recalculate()

      val prop = ObjectProperty[B](originalValue)

      def changeHandler = {
        prop.value = recalculate()
      }

      a onChange changeHandler
      prop
    }

    def point[A](a: => A): ObservableValue[A, A] = {
      ObjectProperty[A](a)
    }

    /* Ap can be derived from `point` and `bind`, but this has less overhead. */
    override def ap[A, B](fa: => Observable[A])(f: => Observable[A => B]): ObservableValue[B, B] = {
      @inline def recalculate(): B = (f.value)(fa.value)

      val originalValue = recalculate()

      val prop = ObjectProperty[B](originalValue)

      var prevValue = originalValue

      def changeHandler = {
        val newVal = recalculate()
        if (prevValue != newVal) {
          prop.value = newVal
          prevValue = newVal
        }
      }

      fa onChange changeHandler
      f onChange changeHandler

      prop
    }

    /* Aka `flatMap` */
    override def bind[A, B](fa: Observable[A])(f: A => Observable[B]): ObservableValue[B, B] = {
      join(map(fa)(f))
    }

    /* Aka `flatten` */
    override def join[A](ooa: Observable[Observable[A]]): ObservableValue[A, A] = {
      @inline def oa() = ooa.value
      @inline def calc(): A = oa().value

      val originalValue = calc()

      val prop = ObjectProperty[A](originalValue)

      var prevValue = originalValue

      def innerHandle() = {
        val newVal = calc()
        if (prevValue != newVal) {
          prop.value = newVal
          prevValue = newVal
        }
      }
      var innerSub = oa() onChange innerHandle

      var prevOuter = oa()
      def outerHandle() = {
        val newOuter = oa()
        /* We need reference equality here: we're subscribing to a specific object. */
        if (prevOuter ne newOuter) {
          innerSub.cancel()
          innerSub = newOuter onChange innerHandle
          prevOuter = newOuter
          innerHandle()
        }
      }

      ooa onChange outerHandle

      prop
    }
  }

  implicit def enrichObservable[A, B](o: ObservableValue[A, B]) = new RichObservable(o)
  implicit def enrichProperty[A, B](o: Property[A, B]) = new RichProperty(o)
  implicit def enrichTuple[A <: Product](a: A) = new RichTuple(a)
}

class RichTuple[A <: Product](val self: A) extends AnyVal {
  import shapeless._
  import shapeless.syntax._
  import shapeless.ops.hlist._

  /* It's possible to do this operation without conversion directly using
   * Shapeless's `tuple` package, but it can't infer the exact output type,
   * which is far less useful.
   */
  def observe
      [L <: HList, Unwrapped <: HList, Tupled <: Product]
      (implicit tohl: Generic.Aux[A, L],
                lister: ToTraversable.Aux[L, List, Observable[_]],
                uw: Mapper.Aux[ObservableUnwrapper.type, L, Unwrapped],
                tplr: Tupler.Aux[Unwrapped, Tupled]): ObservableValue[Tupled, Tupled] = {
    val asHList: L = tohl.to(self)
    def calculate(): Tupled = uw(asHList).tupled

    val original = calculate()
    val prop = ObjectProperty[Tupled](original)

    for {
      component <- asHList.to[List]
    } {
      component onChange {
        prop.value = calculate()
      }
    }
    prop
  }

  //  def omap[B]
}

class RichObservable[A, C](val self: ObservableValue[A, C]) extends AnyVal {
  private type ObjObs[X] = ObservableValue[X, X]
  @inline private def oapp = observableInstances

  def map[B](f: A => B) = oapp.map(self)(f)
  def flatMap[B](f: A => Observable[B]) = oapp.bind(self)(f)
  def <*>[B](f: Observable[A => B]): ObservableValue[B, B] = oapp.ap(self)(f)
  def tuple[B](f: Observable[B]): Observable[(A,B)] = oapp.tuple2(self, f)
  final def *>[B](fb: ObjObs[B]): Observable[B] = oapp.apply2(self,fb)((_,b) => b)
  final def <*[B](fb: ObjObs[B]): Observable[A] = oapp.apply2(self,fb)((a,_) => a)

  final def |@|[B, B1](fb: ObservableValue[B, B1]) = ObservableTupler(self, fb)

  /** Alias for `|@|` */
  final def ⊛[B, B1](fb: ObservableValue[B, B1]) = |@|(fb)
}

class RichProperty[A, B](val inner: Property[A, B]) extends AnyVal {
  def biMap[B <: AnyRef](push: A => B, pull: B => A): ObjectProperty[B] = {
    val original = push(inner.value)
    val op = ObjectProperty[B](original)
    inner onChange {
      val oldVal = op.value
      val newVal = push(inner.value)
      if (oldVal != newVal) {
        op.value = push(inner.value)
      }
    }
    op onChange {
      val oldVal = inner.value
      val newVal = pull(op.value)
      if (oldVal != newVal) {
        inner.value = newVal
      }
    }
    op
  }
}
