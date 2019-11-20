package io.epifab.tydal

import io.epifab.tydal.utils.TaggedFinder
import shapeless.HList
import shapeless.ops.hlist.Tupler

class TableBuilder[TableName <: String with Singleton, Schema](implicit name: ValueOf[TableName]) {
  def as[Alias <: String with Singleton, Repr <: HList](alias: Alias)(implicit a: ValueOf[Alias], schemaBuilder: SchemaBuilder[Schema, Repr]): Table[Repr] with Tagging[Alias] =
    Table(name.value, schemaBuilder.build(alias), alias)
}

class Table[Fields <: HList] private(val tableName: String, override val fields: Fields) extends Selectable[Fields] with FindContext[Fields] { self: Tagging[_] =>
  override def apply[T <: String with Singleton, X](tag: T)(implicit finder: TaggedFinder[T, X, Fields]): X with Tagging[T] =
    finder.find(fields)

  def `*`[T](implicit tupler: Tupler.Aux[Fields, T]): T = tupler(fields)
}

object Table {
  def unapply(table: Table[_]): Option[String] = Some(table.tableName)

  protected[tydal] def apply[Fields <: HList, Alias <: String with Singleton](tableName: String, fields: Fields, tableAlias: String): Table[Fields] with Tagging[Alias] = new Table[Fields](tableName, fields) with Tagging[Alias] {
    override def tagValue: String = tableAlias
  }
}
