package io.epifab

import io.epifab.tydal.schema.{BinaryExpr, BinaryExprOption, Column, FieldDecoder, FieldEncoder, Literal}

package object tydal {

  type Tag = String with Singleton

  type As[+T, A <: String with Singleton] = T with Tagging[A]

  type :=:[A <: String with Singleton, T] = Column[T] with Tagging[A]
  type ~~>[A <: String with Singleton, T] = Literal[T] with Tagging[A]

  implicit class ExtendedTag[A <: String with Singleton](tag: A)(implicit valueOf: ValueOf[A]) {
    def ~~>[T](value: T)(implicit encoder: FieldEncoder[T], decoder: FieldDecoder[T]): Literal[T] with Tagging[A] = Literal(value).as(tag)
  }

  implicit class ExtendedOptionBinaryExpr[+E <: BinaryExpr](val option: Option[E]) {
    def toExpr: BinaryExprOption[E] = new BinaryExprOption[E] {
      override def expr: Option[E] = option
    }
  }
}
