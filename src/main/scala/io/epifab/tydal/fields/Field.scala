package io.epifab.tydal.fields

import io.epifab.tydal.{SelectQuery, Tag, Tagging}
import shapeless.{::, HList, HNil}

import scala.annotation.implicitNotFound

sealed trait Field[+T] {
  def decoder: FieldDecoder[T]
  def as[Alias <: String with Singleton](alias: Alias): Field[T] with Tagging[Alias]
}

case class Column[+T](name: String, relationAlias: String)(implicit val decoder: FieldDecoder[T]) extends Field[T] {
  override def as[Alias <: String with Singleton](alias: Alias): Column[T] with Tagging[Alias] =
    new Column[T](name, relationAlias) with Tagging[Alias] {
      override def tagValue: String = alias
    }
}

case class Aggregation[+F <: Field[_], +U](field: F, dbFunction: DbAggregationFunction[F, U])(implicit val decoder: FieldDecoder[U])
  extends Field[U] {
  override def as[Alias <: String with Singleton](alias: Alias): Aggregation[F, U] with Tagging[Alias] =
    new Aggregation(field, dbFunction) with Tagging[Alias] {
      override def tagValue: String = alias
    }
}

case class Cast[+F <: Field[_], +U](field: F)(implicit val decoder: FieldDecoder[U])
  extends Field[U] {
  override def as[Alias <: String with Singleton](alias: Alias): Cast[F, U] with Tagging[Alias] =
    new Cast(field) with Tagging[Alias] {
      override def tagValue: String = alias
    }
}

case class SoftCast[+F <: Field[_], +T] private[fields](field: F)(implicit val decoder: FieldDecoder[T])
  extends Field[T] {
  override def as[Alias <: String with Singleton](alias: Alias): SoftCast[F, T] with Tagging[Alias] =
    new SoftCast[F, T](field) with Tagging[Alias] {
      override def tagValue: String = alias
    }
}

object Nullable {
  def apply[F <: Field[_], G <: Field[_]]
      (field: F)
      (implicit nullableField: NullableField[F, G]): G =
    nullableField(field)
}

case class FieldExpr1[+F <: Field[_], +U](field: F, dbFunction: DbFunction1[F, U])(implicit val decoder: FieldDecoder[U])
  extends Field[U] {
  override def as[Alias <: String with Singleton](alias: Alias): FieldExpr1[F, U] with Tagging[Alias] =
    new FieldExpr1(field, dbFunction) with Tagging[Alias] {
      override def tagValue: String = alias
    }
}

case class FieldExpr2[+F1 <: Field[_], +F2 <: Field[_], +U](field1: F1, field2: F2, dbFunction: DbFunction2[F1, F2, U])(implicit val decoder: FieldDecoder[U])
  extends Field[U] {
  override def as[Alias <: String with Singleton](alias: Alias): FieldExpr2[F1, F2, U] with Tagging[Alias] =
    new FieldExpr2(field1, field2, dbFunction) with Tagging[Alias] {
      override def tagValue: String = alias
    }
}

trait Placeholder[T] extends Field[T]

class NamedPlaceholder[T] private(val name: String)(implicit val decoder: FieldDecoder[T], val encoder: FieldEncoder[T])
  extends Placeholder[T] {

  def as[Alias <: String with Singleton](newName: Alias): NamedPlaceholder[T] with Tagging[Alias] =
    new NamedPlaceholder[T](newName) with Tagging[Alias] {
      override def tagValue: String = newName
    }

  override def equals(obj: Any): Boolean = obj match {
    case p: NamedPlaceholder[T] => p.name == name
    case _ => false
  }

  override def toString: String = s"Placeholder($name)"
}

object NamedPlaceholder {
  def apply[T, Name <: String with Singleton]
  (implicit
   name: ValueOf[Name],
   encoder: FieldEncoder[T],
   decoder: FieldDecoder[T]): NamedPlaceholder[T] with Tagging[Name] =
    new NamedPlaceholder(name.value)(decoder, encoder) with Tagging[Name] {
      override def tagValue: String = name
    }
}

class PlaceholderValue[T](val value: T)(implicit val decoder: FieldDecoder[T], val encoder: FieldEncoder[T])
  extends Placeholder[T] {
  def dbValue: encoder.DbType = encoder.encode(value)

  override def as[Alias <: String with Singleton](alias: Alias): PlaceholderValue[T] with Tagging[Alias] =
    new PlaceholderValue[T](value) with Tagging[Alias] {
      override def tagValue: String = alias
    }
}

object PlaceholderValue {
  def apply[Value]
  (value: Value)
  (implicit
   encoder: FieldEncoder[Value],
   decoder: FieldDecoder[Value]): PlaceholderValue[Value] =
    new PlaceholderValue(value)
}

class PlaceholderValueOption[T] private(val value: Option[PlaceholderValue[T]])(implicit val decoder: FieldDecoder[Option[T]], val encoder: FieldEncoder[T])
  extends Placeholder[Option[T]] {
  override def as[Alias <: String with Singleton](alias: Alias): PlaceholderValueOption[T] with Tagging[Alias] = new PlaceholderValueOption(value) with Tagging[Alias] {
    override def tagValue: String = alias
  }
}

object PlaceholderValueOption {
  def apply[T]
  (value: Option[T])
  (implicit
   decoder: FieldDecoder[T],
   encoder: FieldEncoder[T]): PlaceholderValueOption[T] =
    new PlaceholderValueOption(value.map(new PlaceholderValue(_)))(decoder.toOption, encoder)
}

trait FieldT[-F <: Field[_], T] {
  def get(f: F): Field[T]
}

object FieldT {
  implicit def pure[T]: FieldT[Field[T], T] = (field: Field[T]) => field
}

object Field {
  implicit class ExtendedField[F1 <: Field[_]](field1: F1) {
    def ===[F2 <: Field[_]](field2: F2)(implicit comparable: AreComparable[F1, F2]): Equals[F1, F2] =
      Equals(field1, field2)

    def like[F2 <: Field[_]](field2: F2)(implicit leftIsText: IsText[F1], rightIsText: IsText[F2]): Like[F1, F2] =
      Like(field1, field2)

    def !==[F2 <: Field[_]](field2: F2)(implicit comparable: AreComparable[F1, F2]): NotEquals[F1, F2] =
      NotEquals(field1, field2)

    def <[F2 <: Field[_]](field2: F2)(implicit comparable: AreComparable[F1, F2]): LessThan[F1, F2] =
      LessThan(field1, field2)

    def >[F2 <: Field[_]](field2: F2)(implicit comparable: AreComparable[F1, F2]): GreaterThan[F1, F2] =
      GreaterThan(field1, field2)

    def <=[F2 <: Field[_]](field2: F2)(implicit comparable: AreComparable[F1, F2]): LessThanOrEqual[F1, F2] =
      LessThanOrEqual(field1, field2)

    def >=[F2 <: Field[_]](field2: F2)(implicit comparable: AreComparable[F1, F2]): GreaterThanOrEqual[F1, F2] =
      GreaterThanOrEqual(field1, field2)

    def subsetOf[F2 <: Field[_]](field2: F2)(implicit areComparableSeq: AreComparableSeq[F1, F2]): IsSubset[F1, F2] =
      IsSubset(field1, field2)

    def supersetOf[F2 <: Field[_]](field2: F2)(implicit areComparableSeq: AreComparableSeq[F1, F2]): IsSuperset[F1, F2] =
      IsSuperset(field1, field2)

    def overlaps[F2 <: Field[_]](field2: F2)(implicit areComparableSeq: AreComparableSeq[F1, F2]): Overlaps[F1, F2] =
      Overlaps(field1, field2)

    def in[F2 <: Field[_]](field2: F2)(implicit canBeIncluded: CanBeIncluded[F1, F2]): IsIncluded[F1, F2] =
      IsIncluded(field1, field2)

    def in[
      Placeholders <: HList,
      F2 <: Field[_],
      GroupBy <: HList,
      Sources <: HList,
      Where <: BinaryExpr,
      Having <: BinaryExpr,
      Sort <: HList
    ](subQuery: SelectQuery[F2 :: HNil, GroupBy, Sources, Where, Having, Sort])(implicit areComparable: AreComparable[F1, F2]): InSubquery[F1, F2, GroupBy, Sources, Where, Having, Sort] =
      InSubquery(field1, subQuery)

    def ===[PlaceholderName <: String with Singleton, T]
    (placeholderName: PlaceholderName)
    (implicit
     valueOf: ValueOf[PlaceholderName],
     fieldT: FieldT[F1, T],
     fieldEncoder: FieldEncoder[T],
     fieldDecoder: FieldDecoder[T],
     comparable: AreComparable[F1, NamedPlaceholder[T] with Tagging[PlaceholderName]]): Equals[F1, NamedPlaceholder[T] with Tagging[PlaceholderName]] =
      Equals(field1, NamedPlaceholder[T, PlaceholderName])

    def like[PlaceholderName <: String with Singleton]
    (placeholderName: PlaceholderName)
    (implicit
     valueOf: ValueOf[PlaceholderName],
     isText: IsText[F1],
     fieldEncoder: FieldEncoder[String],
     fieldDecoder: FieldDecoder[String]): Like[F1, NamedPlaceholder[String] with Tagging[PlaceholderName]] =
      Like(field1, NamedPlaceholder[String, PlaceholderName])

    def !==[PlaceholderName <: String with Singleton, T]
    (placeholderName: PlaceholderName)
    (implicit
     valueOf: ValueOf[PlaceholderName],
     fieldT: FieldT[F1, T],
     fieldEncoder: FieldEncoder[T],
     fieldDecoder: FieldDecoder[T],
     comparable: AreComparable[F1, NamedPlaceholder[T] with Tagging[PlaceholderName]]): NotEquals[F1, NamedPlaceholder[T] with Tagging[PlaceholderName]] =
      NotEquals(field1, NamedPlaceholder[T, PlaceholderName])

    def <[PlaceholderName <: String with Singleton, T]
    (placeholderName: PlaceholderName)
    (implicit
     valueOf: ValueOf[PlaceholderName],
     fieldT: FieldT[F1, T],
     fieldEncoder: FieldEncoder[T],
     fieldDecoder: FieldDecoder[T],
     comparable: AreComparable[F1, NamedPlaceholder[T] with Tagging[PlaceholderName]]): LessThan[F1, NamedPlaceholder[T] with Tagging[PlaceholderName]] =
      LessThan(field1, NamedPlaceholder[T, PlaceholderName])

    def >[PlaceholderName <: String with Singleton, T]
    (placeholderName: PlaceholderName)
    (implicit
     valueOf: ValueOf[PlaceholderName],
     fieldT: FieldT[F1, T],
     fieldEncoder: FieldEncoder[T],
     fieldDecoder: FieldDecoder[T],
     comparable: AreComparable[F1, NamedPlaceholder[T] with Tagging[PlaceholderName]]): GreaterThan[F1, NamedPlaceholder[T] with Tagging[PlaceholderName]] =
      GreaterThan(field1, NamedPlaceholder[T, PlaceholderName])

    def <=[PlaceholderName <: String with Singleton, T]
    (placeholderName: PlaceholderName)
    (implicit
     valueOf: ValueOf[PlaceholderName],
     fieldT: FieldT[F1, T],
     fieldEncoder: FieldEncoder[T],
     fieldDecoder: FieldDecoder[T],
     comparable: AreComparable[F1, NamedPlaceholder[T] with Tagging[PlaceholderName]]): LessThanOrEqual[F1, NamedPlaceholder[T] with Tagging[PlaceholderName]] =
      LessThanOrEqual(field1, NamedPlaceholder[T, PlaceholderName])

    def >=[PlaceholderName <: String with Singleton, T]
    (placeholderName: PlaceholderName)
    (implicit
     valueOf: ValueOf[PlaceholderName],
     fieldT: FieldT[F1, T],
     fieldEncoder: FieldEncoder[T],
     fieldDecoder: FieldDecoder[T],
     comparable: AreComparable[F1, NamedPlaceholder[T] with Tagging[PlaceholderName]]): GreaterThanOrEqual[F1, NamedPlaceholder[T] with Tagging[PlaceholderName]] = GreaterThanOrEqual(field1, NamedPlaceholder[T, PlaceholderName])

    def subsetOf[PlaceholderName <: String with Singleton, T]
    (placeholderName: PlaceholderName)
    (implicit
     valueOf: ValueOf[PlaceholderName],
     fieldT: FieldT[F1, T],
     fieldEncoder: FieldEncoder[T],
     fieldDecoder: FieldDecoder[T],
     areComparableSeq: AreComparableSeq[F1, NamedPlaceholder[T] with Tagging[PlaceholderName]]): IsSubset[F1, NamedPlaceholder[T] with Tagging[PlaceholderName]] =
      IsSubset(field1, NamedPlaceholder[T, PlaceholderName])

    def supersetOf[PlaceholderName <: String with Singleton, T]
    (placeholderName: PlaceholderName)
    (implicit
     valueOf: ValueOf[PlaceholderName],
     fieldT: FieldT[F1, T],
     fieldEncoder: FieldEncoder[T],
     fieldDecoder: FieldDecoder[T],
     areComparableSeq: AreComparableSeq[F1, NamedPlaceholder[T] with Tagging[PlaceholderName]]): IsSuperset[F1, NamedPlaceholder[T] with Tagging[PlaceholderName]] =
      IsSuperset(field1, NamedPlaceholder[T, PlaceholderName])

    def overlaps[PlaceholderName <: String with Singleton, T]
    (placeholderName: PlaceholderName)
    (implicit
     valueOf: ValueOf[PlaceholderName],
     fieldT: FieldT[F1, T],
     fieldEncoder: FieldEncoder[T],
     fieldDecoder: FieldDecoder[T],
     areComparableSeq: AreComparableSeq[F1, NamedPlaceholder[T] with Tagging[PlaceholderName]]): Overlaps[F1, NamedPlaceholder[T] with Tagging[PlaceholderName]] =
      Overlaps(field1, NamedPlaceholder[T, PlaceholderName])

    def in[PlaceholderName <: String with Singleton, T]
    (placeholderName: PlaceholderName)
    (implicit
     valueOf: ValueOf[PlaceholderName],
     fieldT: FieldT[F1, T],
     fieldEncoder: FieldEncoder[Seq[T]],
     fieldDecoder: FieldDecoder[Seq[T]],
     canBeIncluded: CanBeIncluded[F1, NamedPlaceholder[Seq[T]] with Tagging[PlaceholderName]]): IsIncluded[F1, NamedPlaceholder[Seq[T]] with Tagging[PlaceholderName]] =
      IsIncluded(field1, NamedPlaceholder[Seq[T], PlaceholderName])
  }
}
