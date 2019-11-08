package io.epifab.tydal

import io.epifab.tydal.fields._

object Implicits {
  implicit class ExtendedTag[A <: String with Singleton](tag: A)(implicit valueOf: ValueOf[A]) {
    def ~~>[T](value: T)(implicit encoder: FieldEncoder[T], decoder: FieldDecoder[T]): PlaceholderValue[T] with Tag[A] = PlaceholderValue(tag, value)
    def ?[T](implicit fieldEncoder: FieldEncoder[T], fieldDecoder: FieldDecoder[T]): NamedPlaceholder[T] with Tag[A] = NamedPlaceholder[T, A]
  }
}
