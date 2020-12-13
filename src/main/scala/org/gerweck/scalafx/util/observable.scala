package org.gerweck.scalafx.util

import language.implicitConversions

import cats._

import scalafx.beans.property._
import scalafx.beans.value._
import scalafx.collections._

trait ObservableImplicits {
  /* NOTE: (Sarah) I believe that the synchronization in these helpers is not
   * _really_ required in the JavaFX threading model. However, the overhead of
   * uncontended synchronization is relatively low, and typical UIs won't have
   * enough change events for it to be a serious issue. (If you're updating
   * a property in a tight loop, I expect you'll have bigger performance
   * issues.)
   */
  implicit val observableInstances: Applicative[Observable] with Functor[Observable] with Monad[Observable] = ObservableImplicits.ObservableInstances
  implicit val readOnlyObjectPropertyInstances: Applicative[ReadOnlyObjectProperty] with Functor[ReadOnlyObjectProperty] with Monad[ReadOnlyObjectProperty] = ObservableImplicits.ReadOnlyObjectPropertyInstances

  implicit def enrichObservable[A, B](o: ObservableValue[A, B]) = new RichObservable(o)
  implicit def enrichObservableOfIterable[A, B](ooi: ObservableValue[B, B])(implicit ev1: B => Iterable[A]) = new ObservableOfIterable[A, B](ooi)
  implicit def enrichObservableOfMapLike[A, B, C](ooml: ObservableValue[C, C])(implicit ev1: C => Iterable[(A, B)]) = new ObservableOfMapLike[A, B, C](ooml)
  implicit def enrichProperty[A, B](o: Property[A, B]) = new RichProperty(o)
  implicit def enrichObjectProperty[A](o: ObjectProperty[A]) = new RichObjectProperty(o)
  implicit def enrichTuple[A <: Product](a: A) = new RichTuple(a)

  implicit def enrichObservableBuffer[A](ob: ObservableBuffer[A]) = new RichObservableBuffer(ob)
  implicit def enrichObservableArray[A, B <: ObservableArray[A, B, C], C <: javafx.collections.ObservableArray[C]](oa: ObservableArray[A, B, C]) = new RichObservableArray(oa)
  implicit def enrichObservableSet[A](os: ObservableSet[A]) = new RichObservableSet(os)
}

private object ObservableImplicits {
  object ObservableInstances extends Applicative[Observable] with Functor[Observable] with Monad[Observable] {
    /* Map can be derived from `ap`, but this adds less overhead. */
    override def map[A, B](a: Observable[A])(f: A => B): ReadOnlyObjectProperty[B] = {
      @inline def recalculate(): B = f(a.value)

      val originalValue = recalculate()

      val prop = ObjectProperty[B](originalValue)

      var prevValue = originalValue
      def changeHandler() = prop.synchronized {
        val newVal = recalculate()
        if (prevValue != newVal) {
          prop.value = newVal
          prevValue = newVal
        }
      }

      a onChange changeHandler()
      prop
    }

    override def pure[A](a: A): ReadOnlyObjectProperty[A] = {
      ObjectProperty[A](a)
    }

    /* Ap can be derived from `point` and `bind`, but this has less overhead. */
    override def ap[A, B](f: Observable[A => B])(fa: Observable[A]): ReadOnlyObjectProperty[B] = {
      @inline def recalculate(): B = (f.value)(fa.value)

      val originalValue = recalculate()

      val prop = ObjectProperty[B](originalValue)

      var prevValue = originalValue

      def changeHandler() = prop.synchronized {
        val newVal = recalculate()
        if (prevValue != newVal) {
          prop.value = newVal
          prevValue = newVal
        }
      }

      fa onChange changeHandler()
      f onChange changeHandler()

      prop
    }

    override def flatMap[A, B](fa: Observable[A])(f: A => Observable[B]): ReadOnlyObjectProperty[B] = {
      flatten(map(fa)(f))
    }

    override def tailRecM[A, B](a: A)(f: A => Observable[Either[A, B]]): Observable[B] = {
      this.flatMap(f(a)) {
        case Right(b)    => pure(b)
        case Left(nextA) => tailRecM(nextA)(f)
      }
    }

    override def flatten[A](ooa: Observable[Observable[A]]): ReadOnlyObjectProperty[A] = {
      @inline def oa() = ooa.value
      @inline def calc(): A = oa().value

      val originalValue = calc()

      val prop = ObjectProperty[A](originalValue)

      var prevValue = originalValue

      def innerHandle() = prop.synchronized {
        val newVal = calc()
        if (prevValue != newVal) {
          prop.value = newVal
          prevValue = newVal
        }
      }
      var innerSub = oa() onChange innerHandle()

      var prevOuter = oa()
      def outerHandle() = prop.synchronized {
        val newOuter = oa()
        /* We need reference equality here: we're subscribing to a specific object. */
        if (prevOuter ne newOuter) {
          innerSub.cancel()
          innerSub = newOuter onChange innerHandle()
          prevOuter = newOuter
          innerHandle()
        }
      }

      ooa onChange outerHandle()

      prop
    }
  }

  object ReadOnlyObjectPropertyInstances extends Applicative[ReadOnlyObjectProperty] with Functor[ReadOnlyObjectProperty] with Monad[ReadOnlyObjectProperty] {
    override def map[A, B](a: ReadOnlyObjectProperty[A])(f: A => B): ReadOnlyObjectProperty[B] = ObservableInstances.map(a)(f)
    override def pure[A](a: A): ReadOnlyObjectProperty[A] = ObservableInstances.pure(a)
    override def ap[A, B](f: ReadOnlyObjectProperty[A => B])(fa: ReadOnlyObjectProperty[A]): ReadOnlyObjectProperty[B] = ObservableInstances.ap(f)(fa)
    override def flatMap[A, B](fa: ReadOnlyObjectProperty[A])(f: A => ReadOnlyObjectProperty[B]): ReadOnlyObjectProperty[B] = ObservableInstances.flatMap(fa)(f)
    override def tailRecM[A, B](a: A)(f: A => ReadOnlyObjectProperty[Either[A, B]]): ReadOnlyObjectProperty[B] = {
      flatMap(f(a)) {
        case Right(b)    => pure(b)
        case Left(nextA) => tailRecM(nextA)(f)
      }
    }
    override def flatten[A](ooa: ReadOnlyObjectProperty[ReadOnlyObjectProperty[A]]): ReadOnlyObjectProperty[A] = {
      /* NOTE: this is copy-pasted from `observableInstances`. TBD: Find a way to share this. */
      @inline def oa() = ooa.value
      @inline def calc(): A = oa().value

      val originalValue = calc()

      val prop = ObjectProperty[A](originalValue)

      var prevValue = originalValue

      def innerHandle() = prop.synchronized {
        val newVal = calc()
        if (prevValue != newVal) {
          prop.value = newVal
          prevValue = newVal
        }
      }
      var innerSub = oa() onChange innerHandle()

      var prevOuter = oa()
      def outerHandle() = prop.synchronized {
        val newOuter = oa()
        /* We need reference equality here: we're subscribing to a specific object. */
        if (prevOuter ne newOuter) {
          innerSub.cancel()
          innerSub = newOuter onChange innerHandle()
          prevOuter = newOuter
          innerHandle()
        }
      }

      ooa onChange outerHandle()

      prop
    }
  }
}

final class RichTuple[A <: Product](val self: A) extends AnyVal {
  import shapeless._
  import shapeless.ops.hlist._

  /* It's possible to do this operation without conversion directly using
   * Shapeless's `tuple` package, but it can't infer the exact output type,
   * which is far less useful.
   */
  def observe[L <: HList, Unwrap <: HList, Tupled <: Product]
             (implicit tohl: Generic.Aux[A, L],
              lister: ToTraversable.Aux[L, List, Observable[_]],
              uw: Mapper.Aux[ObservableUnwrapper.type, L, Unwrap],
              tplr: Tupler.Aux[Unwrap, Tupled]): ObservableValue[Tupled, Tupled] = {
    val asHList: L = tohl.to(self)
    def calculate(): Tupled = uw(asHList).tupled

    val original = calculate()
    val prop = ObjectProperty[Tupled](original)

    for (component <- asHList.to[List]) {
      component onChange {
        prop.value = calculate()
      }
    }
    prop
  }
}

final class RichObservable[A, C](val self: ObservableValue[A, C]) extends AnyVal {
  private[this] type ObjObs[X] = ObservableValue[X, X]
  @inline private[this] def oapp = ObservableImplicits.ObservableInstances

  def map[B](f: A => B) = oapp.map(self)(f)
  def flatMap[B](f: A => Observable[B]) = oapp.flatMap(self)(f)
  def <*>[B](f: Observable[A => B]): ObservableValue[B, B] = oapp.ap(f)(self)
  def tuple[B](f: Observable[B]): Observable[(A,B)] = oapp.tuple2(self, f)
  final def *>[B](fb: ObjObs[B]): Observable[B] = oapp.map2(self,fb)((_,b) => b)
  final def <*[B](fb: ObjObs[B]): Observable[A] = oapp.map2(self,fb)((a,_) => a)
  final def >>[B](fb: => ObjObs[B]): Observable[B] = oapp.map2(self, fb)((_, b) => b)
  final def <<[B](fb: => ObjObs[B]): Observable[A] = oapp.map2(self, fb)((a, _) => a)

  final def |@|[B, B1](fb: ObservableValue[B, B1]) = ObservableTupler(self, fb)

  /** Alias for `|@|` */
  final def ⊛[B, B1](fb: ObservableValue[B, B1]) = |@|(fb)
}

final class ObservableOfIterable[A, B](val self: ObservableValue[B, B])(implicit ev1: B => Iterable[A]) {
  def observeBuffer: ObservableBuffer[A] = {
    val buff = ObservableBuffer(self.value.toSeq)
    self onChange { (_, oldV, newV) => fillCollection(buff.delegate, newV) }
    buff
  }
  def observeSet: ObservableSet[A] = {
    val set = ObservableSet[A](self.value.toSet.toSeq: _*)
    self onChange { (_, oldV, newV) =>
      val newSet = newV.toSet
      if (oldV.toSet != newSet) {
        set.clear()
        set ++= newSet
      }
    }
    set
  }
}
final class ObservableOfMapLike[A, B, C](val self: ObservableValue[C, C])(implicit ev1: C => Iterable[(A, B)]) {
  def observeMap: ObservableMap[A, B] = {
    val map = ObservableMap[A, B](self.value.toMap.toSeq: _*)
    self onChange { (_, oldV, newV) =>
      val newMap = newV.toMap
      if (oldV.toMap != newV.toMap) {
        map.clear()
        map ++= newMap
      }
    }
    map
  }
}

final class RichProperty[A, B](val inner: Property[A, B]) extends AnyVal {
  def biMap[A1 <: AnyRef](push: A => A1, pull: A1 => A): ObjectProperty[A1] = {
    val original = push(inner.value)
    val op = ObjectProperty[A1](original)
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

  def readOnly: ReadOnlyProperty[A, B] = inner
}

final class RichObjectProperty[A](val inner: ObjectProperty[A]) extends AnyVal {
  def readOnly: ReadOnlyObjectProperty[A] = inner
}
