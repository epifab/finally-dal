package io.epifab.tydal.fields

import io.epifab.tydal.{Tag, Tagged, Tagging}
import shapeless.{::, Generic, HList, HNil}

trait FieldValues[FIELDS, VALUES] { }

object FieldValues {
  implicit def field[F <: Field[_] with Tagging[_], T, A <: Tag]
      (implicit
       fieldT: FieldT[F, T],
       taggedField: Tagged[F, A]): FieldValues[F, PlaceholderValue[T] with Tagging[A]] =
    new FieldValues[F, PlaceholderValue[T] with Tagging[A]] { }

  implicit val hNil: FieldValues[HNil, HNil] = new FieldValues[HNil, HNil] {}

  implicit def hCons[H, HX, T <: HList, TX <: HList]
      (implicit
       head: FieldValues[H, HX],
       tail: FieldValues[T, TX]): FieldValues[H :: T, HX :: TX] =
    new FieldValues[H :: T, HX :: TX] {}

  implicit def caseClass[FIELDS <: HList, VALUES <: HList, CC]
      (implicit
       generic: Generic.Aux[CC, VALUES],
       values: FieldValues[FIELDS, VALUES]): FieldValues[FIELDS, CC] =
    new FieldValues[FIELDS, CC] {}
}
