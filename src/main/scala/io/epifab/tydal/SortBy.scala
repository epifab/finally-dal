package io.epifab.tydal

import io.epifab.tydal.fields.Field

sealed trait SortOrder

case class SortBy[+F <: Field[_] with Tagging[_]](field: F, sortOrder: SortOrder) {
  def alias: String = field.tagValue
}

object Ascending {
  case object AscendingOrder extends SortOrder

  def apply[F <: Field[_] with Tagging[_]](field: F): SortBy[F] =
    SortBy(field, AscendingOrder)
}

object Descending {
  case object DescendingOrder extends SortOrder

  def apply[F <: Field[_] with Tagging[_]](field: F): SortBy[F] =
    SortBy(field, DescendingOrder)
}
