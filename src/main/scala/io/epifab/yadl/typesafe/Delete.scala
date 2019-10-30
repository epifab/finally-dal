package io.epifab.yadl.typesafe

import io.epifab.yadl.typesafe.fields.{AlwaysTrue, BinaryExpr, ColumnsBuilder}
import io.epifab.yadl.typesafe.runner.{UpdateStatement, UpdateStatementBuilder}
import shapeless.ops.hlist.Tupler
import shapeless.{Generic, HList, HNil}

class Delete[NAME <: String, SCHEMA, E <: BinaryExpr](val table: Table[NAME, SCHEMA], val filter: E) {
  def where[E2 <: BinaryExpr](f: SCHEMA => E2): Delete[NAME, SCHEMA, E2] =
    new Delete(table, f(table.schema))

  def compile[PLACEHOLDERS <: HList, RAW_INPUT <: HList, INPUT]
      (implicit
       queryBuilder: QueryBuilder[this.type, PLACEHOLDERS, HNil],
       statementBuilder: UpdateStatementBuilder[PLACEHOLDERS, RAW_INPUT, INPUT],
       tupler: Tupler.Aux[RAW_INPUT, INPUT]
      ): UpdateStatement[RAW_INPUT, INPUT] =
    statementBuilder.build(queryBuilder.build(this))
}

object Delete {
  def from[NAME <: String, SCHEMA, FIELDS <: HList]
    (tableBuilder: TableBuilder[NAME, SCHEMA])
    (implicit
     name: ValueOf[NAME],
     genericSchema: Generic.Aux[SCHEMA, FIELDS],
     columnsBuilder: ColumnsBuilder[FIELDS]
    ): Delete[NAME, SCHEMA, AlwaysTrue] =
    new Delete(tableBuilder as name.value, AlwaysTrue)
}
