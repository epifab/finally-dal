package io.epifab.tydal

import io.epifab.tydal.utils.TaggedFinder
import shapeless.{Generic, HList}

trait FindContext[HAYSTACK] {
  def apply[T <: Tag, X](tag: T)(implicit finder: TaggedFinder[T, X, HAYSTACK]): X with Tagging[T]
}

trait Selectable[SCHEMA] extends FindContext[SCHEMA] { self: Tagging[_] =>
  def schema: SCHEMA
}

sealed trait SelectableFields[-S <: Selectable[_], FIELDS <: HList] {
  def fields(selectable: S): FIELDS
}

object SelectableFields {
  implicit def table[FIELDS <: HList]: SelectableFields[Table[_, FIELDS], FIELDS] = new SelectableFields[Table[_, FIELDS], FIELDS] {
    override def fields(selectable: Table[_, FIELDS]): FIELDS = selectable.schema
  }

  implicit def subQuery[FIELDS <: HList]: SelectableFields[SelectSubQuery[FIELDS, _], FIELDS] = new SelectableFields[SelectSubQuery[FIELDS, _], FIELDS] {
    override def fields(selectable: SelectSubQuery[FIELDS, _]): FIELDS = selectable.schema
  }
}
