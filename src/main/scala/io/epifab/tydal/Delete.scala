package io.epifab.tydal

import io.epifab.tydal.fields.{AlwaysTrue, BinaryExpr, ColumnsBuilder}
import io.epifab.tydal.runner.{QueryBuilder, StatementBuilder, WriteStatement}
import shapeless.ops.hlist.Tupler
import shapeless.{Generic, HList, HNil}

class Delete[NAME <: Tag, SCHEMA, E <: BinaryExpr](val table: Table[NAME, SCHEMA], val filter: E) {
  def where[E2 <: BinaryExpr](f: SCHEMA => E2): Delete[NAME, SCHEMA, E2] =
    new Delete(table, f(table.schema))

  def compile[PLACEHOLDERS <: HList, RAW_INPUT <: HList, INPUT]
      (implicit
       queryBuilder: QueryBuilder[this.type, PLACEHOLDERS, HNil],
       statementBuilder: StatementBuilder[PLACEHOLDERS, RAW_INPUT, INPUT, HNil],
       tupler: Tupler.Aux[RAW_INPUT, INPUT]
      ): WriteStatement[INPUT, HNil] =
    statementBuilder.build(queryBuilder.build(this)).update
}

object Delete {
  def from[NAME <: Tag, SCHEMA, FIELDS <: HList]
    (tableBuilder: TableBuilder[NAME, SCHEMA])
    (implicit
     name: ValueOf[NAME],
     genericSchema: Generic.Aux[SCHEMA, FIELDS],
     columnsBuilder: ColumnsBuilder[FIELDS]
    ): Delete[NAME, SCHEMA, AlwaysTrue] =
    new Delete(tableBuilder as name.value, AlwaysTrue)
}
