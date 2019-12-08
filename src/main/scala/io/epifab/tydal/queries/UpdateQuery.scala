package io.epifab.tydal.queries

import io.epifab.tydal.runtime.{StatementBuilder, WriteStatement}
import io.epifab.tydal.schema.{AlwaysTrue, Filter, Columns, GenericSchema, Table, TableBuilder}
import shapeless.ops.hlist.Tupler
import shapeless.{Generic, HList, HNil}

final class UpdateQuery[TableFields <: HList, FieldsToUpdate <: HList, Where <: Filter]
    (val table: Table[TableFields], val $fields: FieldsToUpdate, val $where: Where) {

  def fields[P, NewFields <: HList]
    (f: Selectable[TableFields] => P)
    (implicit generic: Generic.Aux[P, NewFields]): UpdateQuery[TableFields, NewFields, Where] =
    new UpdateQuery(table, generic.to(f(table)), $where)

  def where[E2 <: Filter](f: Selectable[TableFields] => E2): UpdateQuery[TableFields, FieldsToUpdate, E2] =
    new UpdateQuery(table, $fields, f(table))

  def compile[Placeholders <: HList, InputRepr <: HList, Input]
      (implicit
       queryBuilder: QueryBuilder[this.type, Placeholders, HNil],
       statementBuilder: StatementBuilder[Placeholders, InputRepr, Input, HNil],
       tupler: Tupler.Aux[InputRepr, Input]
      ): WriteStatement[Input, HNil] =
    statementBuilder.build(queryBuilder.build(this)).update
}

object Update {
  def apply[TableName <: String with Singleton, Schema, Fields <: HList]
      (tableBuilder: TableBuilder[TableName, Schema])
      (implicit
       name: ValueOf[TableName],
       genericSchema: GenericSchema.Aux[Schema, Fields],
       columns: Columns[Fields]
      ): UpdateQuery[Fields, Fields, AlwaysTrue] =
    new UpdateQuery(tableBuilder as name.value, columns(name.value), AlwaysTrue)
}
