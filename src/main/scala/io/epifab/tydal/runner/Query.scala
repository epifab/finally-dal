package io.epifab.tydal.runner

import io.epifab.tydal.Ascending.AscendingOrder
import io.epifab.tydal.Descending.DescendingOrder
import io.epifab.tydal._
import io.epifab.tydal.fields._
import io.epifab.tydal.runner.FragmentType._
import io.epifab.tydal.utils.Concat
import shapeless.{::, Generic, HList, HNil}

import scala.annotation.implicitNotFound

case class Query[+PLACEHOLDERS <: HList, +FIELDS <: HList](sql: String, placeholders: PLACEHOLDERS, fields: FIELDS)

case class QueryFragment[P <: HList](sql: Option[String], placeholders: P) {
  def `++`(s: String): QueryFragment[P] =
    append(s)

  def `++`[Q <: HList, R <: HList](other: QueryFragment[Q])(implicit concat: Concat.Aux[P, Q, R]): QueryFragment[R] =
    concatenateOptional(other, "")

  def `+ +`[Q <: HList, R <: HList](other: QueryFragment[Q])(implicit concat: Concat.Aux[P, Q, R]): QueryFragment[R] =
    concatenateOptional(other, " ")

  def `+,+`[Q <: HList, R <: HList](other: QueryFragment[Q])(implicit concat: Concat.Aux[P, Q, R]): QueryFragment[R] =
    concatenateOptional(other, ", ")

  def wrap(before: String, after: String): QueryFragment[P] =
    QueryFragment(sql.map(before + _ + after), placeholders)

  def append(after: String): QueryFragment[P] =
    QueryFragment(sql.map(_ + after), placeholders)

  def prepend(before: String): QueryFragment[P] =
    QueryFragment(sql.map(before + _), placeholders)

  def map(f: String => String): QueryFragment[P] =
    QueryFragment(sql.map(f), placeholders)

  def concatenateOptional[Q <: HList, R <: HList](other: QueryFragment[Q], separator: String)(implicit concat: Concat.Aux[P, Q, R]): QueryFragment[R] =
    QueryFragment.apply(
      Seq(sql, other.sql)
        .flatten
        .reduceOption(_ ++ separator ++ _),
      concat(placeholders, other.placeholders)
    )

  def concatenateRequired[Q <: HList, R <: HList](other: QueryFragment[Q], separator: String)(implicit concat: Concat.Aux[P, Q, R]): QueryFragment[R] =
    QueryFragment.apply(
      for {
        s1 <- sql
        s2 <- other.sql
      } yield s1 + separator + s2,
      concat(placeholders, other.placeholders)
    )

  def getOrElse[FIELDS <: HList](default: String, fields: FIELDS): Query[P, FIELDS] =
    Query(sql.getOrElse(default), placeholders, fields)

  def orElse(s: Option[String]): QueryFragment[P] =
    new QueryFragment(sql.orElse(s), placeholders)

  def get[FIELDS <: HList](fields: FIELDS): Query[P, FIELDS] =
    Query(sql.get, placeholders, fields)
}

object QueryFragment {
  def empty: QueryFragment[HNil] = QueryFragment(None, HNil)

  def apply[P <: HList](query: Query[P, _]): QueryFragment[P] =
    QueryFragment(Some(query.sql), query.placeholders)

  def apply(sql: String): QueryFragment[HNil] =
    QueryFragment(Some(sql), HNil)

  def apply[P <: Placeholder[_]](sql: String, placeholder: P): QueryFragment[P :: HNil] =
    QueryFragment(Some(sql), placeholder :: HNil)
}

@implicitNotFound("Could not find a query builder for ${X}")
sealed trait QueryBuilder[-X, P <: HList, F <: HList] {
  def build(x: X): Query[P, F]
}

object QueryBuilder {
  def apply[X, P <: HList, F <: HList](x: X)(implicit queryBuilder: QueryBuilder[X, P, F]): Query[P, F] =
    queryBuilder.build(x)

  def instance[T, P <: HList, F <: HList](f: T => Query[P, F]): QueryBuilder[T, P, F] =
    new QueryBuilder[T, P, F] {
      override def build(x: T): Query[P, F] = f(x)
    }

  implicit def selectQuery[
    FIELDS <: HList, GROUP_BY <: HList, SOURCES <: HList, WHERE <: BinaryExpr, HAVING <: BinaryExpr, SORT_BY <: HList,
    P1 <: HList, P2 <: HList, P3 <: HList, P4 <: HList, P5 <: HList,
    P6 <: HList, P7 <: HList, P8 <: HList, P9 <: HList, P10 <: HList, P11 <: HList
  ]
    (implicit
     fields: QueryFragmentBuilder[FT_FieldExprAndAliasList, FIELDS, P1],
     from: QueryFragmentBuilder[FT_From, SOURCES, P2],
     concat1: Concat.Aux[P1, P2, P3],
     where: QueryFragmentBuilder[FT_Where, WHERE, P4],
     concat2: Concat.Aux[P3, P4, P5],
     groupBy: QueryFragmentBuilder[FT_FieldExprList, GROUP_BY, P6],
     concat3: Concat.Aux[P5, P6, P7],
     having: QueryFragmentBuilder[FT_Where, HAVING, P8],
     concat4: Concat.Aux[P7, P8, P9],
     sortBy: QueryFragmentBuilder[FT_SortBy, SORT_BY, P10],
     concat5: Concat.Aux[P9, P10, P11]
    ): QueryBuilder[Select[FIELDS, GROUP_BY, SOURCES, WHERE, HAVING, SORT_BY], P11, FIELDS] =
    QueryBuilder.instance(select =>
      (fields.build(select.$fields).orElse(Some("1")).prepend("SELECT ") `+ +`
        from.build(select.$sources).prepend("FROM ") `+ +`
        where.build(select.$where).prepend("WHERE ") `+ +`
        groupBy.build(select.$groupBy).prepend("GROUP BY ") `+ +`
        having.build(select.$having).prepend("HAVING ") `+ +`
        sortBy.build(select.$sortBy).prepend("ORDER BY ")
      ).get(select.$fields)
    )

  implicit def insertQuery[NAME <: Tag, SCHEMA, COLUMNS <: HList, PLACEHOLDERS <: HList]
      (implicit
       generic: Generic.Aux[SCHEMA, COLUMNS],
       names: QueryFragmentBuilder[FT_ColumnNameList, COLUMNS, HNil],
       placeholders: QueryFragmentBuilder[FT_PlaceholderList, COLUMNS, PLACEHOLDERS]): QueryBuilder[Insert[NAME, SCHEMA], PLACEHOLDERS, HNil] =
    QueryBuilder.instance(insert => {
      val columns = generic.to(insert.table.schema)
      (names.build(columns).wrap("(", ")") ++
        " VALUES " ++
        placeholders.build(columns).wrap("(", ")")
      ).prepend("INSERT INTO " + insert.table.tableName + " ").get(HNil)
    })

  implicit def updateQuery[NAME <: Tag, SCHEMA, COLUMNS <: HList, P <: HList, Q <: HList, R <: HList, WHERE <: BinaryExpr]
      (implicit
       placeholders: QueryFragmentBuilder[FT_ColumnNameAndPlaceholderList, COLUMNS, P],
       where: QueryFragmentBuilder[FT_Where, WHERE, Q],
       concat: Concat.Aux[P, Q, R]
      ): QueryBuilder[Update[NAME, SCHEMA, COLUMNS, WHERE], R, HNil] =
    QueryBuilder.instance(update => {
      (placeholders.build(update.fields).prepend("UPDATE " + update.table.tableName + " SET ") ++
        where.build(update.where).prepend(" WHERE ")).get(HNil)
    })

  implicit def deleteQuery[NAME <: Tag, SCHEMA, P <: HList, WHERE <: BinaryExpr]
      (implicit
       where: QueryFragmentBuilder[FT_Where, WHERE, P]
      ): QueryBuilder[Delete[NAME, SCHEMA, WHERE], P, HNil] =
    QueryBuilder.instance(delete =>
      (QueryFragment(s"DELETE FROM ${delete.table.tableName}") ++
      where.build(delete.filter).prepend(" WHERE "))
          .get(HNil)
    )
}

sealed trait QueryFragmentBuilder[TYPE, -X, P <: HList] {
  def build(x: X): QueryFragment[P]
}

object FragmentType {
  type FT_FieldExprAndAliasList = "FieldExprAndAliasList"
  type FT_FieldExprList = "FieldExprList"
  type FT_From = "From"
  type FT_Where = "FT_Where"
  type FT_ColumnNameList = "ColumnNameList"
  type FT_PlaceholderList = "PlaceholderList"
  type FT_ColumnNameAndPlaceholderList = "ColumnNameAndPlaceholderList"
  type FT_SortBy = "SortByList"
}

object QueryFragmentBuilder {
  class PartialQFB[FT] {
    def apply[T, P <: HList](t: T)(implicit queryFragmentBuilder: QueryFragmentBuilder[FT, T, P]): QueryFragmentBuilder[FT, T, P] =
      queryFragmentBuilder
  }

  def apply[FT] = new PartialQFB[FT]

  def instance[A, T, P <: HList](f: T => QueryFragment[P]): QueryFragmentBuilder[A, T, P] =
    new QueryFragmentBuilder[A, T, P] {
      override def build(x: T): QueryFragment[P] = f(x)
    }

  implicit def fromEmptySources: QueryFragmentBuilder[FT_From, HNil, HNil] =
    QueryFragmentBuilder.instance(_ => QueryFragment.empty)

  implicit def fromNonEmptySources[H, T <: HList, P <: HList, Q <: HList, R <: HList]
      (implicit
       head: QueryFragmentBuilder[FT_From, H, P],
       tail: QueryFragmentBuilder[FT_From, T, Q],
       concat: Concat.Aux[P, Q, R]): QueryFragmentBuilder[FT_From, H :: T, R] =
    instance { sources =>
      head.build(sources.head) `+ +` tail.build(sources.tail)
    }

  implicit def fromJoin[S <: Selectable[_] with Tagging[_], WHERE <: BinaryExpr, P <: HList, Q <: HList, R <: HList]
      (implicit
       src: QueryFragmentBuilder[FT_From, S, P],
       where: QueryFragmentBuilder[FT_Where, WHERE, Q],
       concat: Concat.Aux[P, Q, R]): QueryFragmentBuilder[FT_From, Join[S, WHERE], R] =
    instance(join =>
      src.build(join.selectable).prepend("INNER JOIN ") ++
        where.build(join.filter).prepend(" ON ")
    )

  implicit val fromTable: QueryFragmentBuilder[FT_From, Table[_, _] with Tagging[_], HNil] =
    QueryFragmentBuilder.instance(table => QueryFragment(table.tableName + " AS " + table.tagValue))

  implicit def fromSubQuery[SUBQUERY_FIELDS <: HList, S <: Select[_, _, _, _, _, _], P <: HList]
      (implicit query: QueryBuilder[S, P, _]): QueryFragmentBuilder[FT_From, SelectSubQuery[SUBQUERY_FIELDS, S] with Tagging[_], P] =
    instance(subQuery =>
      QueryFragment(query.build(subQuery.select))
        .wrap("(", ") AS " + subQuery.tagValue)
    )

  // ------------------------------
  // Src and alias lists
  // ------------------------------

  implicit def fieldExprAndAliasField[P <: HList, F <: Tagging[_]](implicit src: QueryFragmentBuilder[FT_FieldExprList, F, P]): QueryFragmentBuilder[FT_FieldExprAndAliasList, F, P] =
    instance((f: F) => src.build(f).append(" AS " + f.tagValue))

  implicit def fieldExprAndAliasEmptyList: QueryFragmentBuilder[FT_FieldExprAndAliasList, HNil, HNil] =
    QueryFragmentBuilder.instance(_ => QueryFragment.empty)

  implicit def fieldExprAndAliasNonEmptyList[H, T <: HList, P <: HList, Q <: HList, R <: HList]
      (implicit
       head: QueryFragmentBuilder[FT_FieldExprAndAliasList, H, P],
       tail: QueryFragmentBuilder[FT_FieldExprAndAliasList, T, Q],
       concat: Concat.Aux[P, Q, R]): QueryFragmentBuilder[FT_FieldExprAndAliasList, H :: T, R] =
    QueryFragmentBuilder.instance(fields =>
      head.build(fields.head) `+,+` tail.build(fields.tail)
    )

  // ------------------------------
  // Src lists
  // ------------------------------

  implicit def fieldExprEmptyList: QueryFragmentBuilder[FT_FieldExprList, HNil, HNil] =
    QueryFragmentBuilder.instance(_ => QueryFragment.empty)

  implicit def fieldExprNonEmptyList[H, T <: HList, P <: HList, Q <: HList, R <: HList]
      (implicit
       head: QueryFragmentBuilder[FT_FieldExprList, H, P],
       tail: QueryFragmentBuilder[FT_FieldExprList, T, Q],
       concat: Concat.Aux[P, Q, R]): QueryFragmentBuilder[FT_FieldExprList, H :: T, R] =
    QueryFragmentBuilder.instance(fields =>
      head.build(fields.head) `+,+` tail.build(fields.tail)
    )

  // ------------------------------
  // Sort by
  // ------------------------------

  implicit def sortByField[S <: SortBy[_]]: QueryFragmentBuilder[FT_SortBy, S, HNil] =
    QueryFragmentBuilder.instance(sortBy => QueryFragment(sortBy.alias + " " + (sortBy.sortOrder match {
      case AscendingOrder => "ASC"
      case DescendingOrder => "DESC"
    })))

  implicit def sortByEmptyList: QueryFragmentBuilder[FT_SortBy, HNil, HNil] =
    QueryFragmentBuilder.instance(_ => QueryFragment.empty)

  implicit def sortByNonEmptyList[H, T <: HList]
      (implicit
       head: QueryFragmentBuilder[FT_SortBy, H, HNil],
       tail: QueryFragmentBuilder[FT_SortBy, T, HNil]): QueryFragmentBuilder[FT_SortBy, H :: T, HNil] =
    instance(clauses => head.build(clauses.head) `+,+` tail.build(clauses.tail))

  // ------------------------------
  // Field (src)
  // ------------------------------

  implicit val fieldExprColumn: QueryFragmentBuilder[FT_FieldExprList, Column[_], HNil] =
    instance((f: Column[_]) => QueryFragment(s"${f.relationAlias}.${f.name}"))

  implicit def fieldExprCast[F <: Field[_], P <: HList](implicit builder: QueryFragmentBuilder[FT_FieldExprList, F, P]): QueryFragmentBuilder[FT_FieldExprList, Cast[F, _], P] =
    instance((f: Cast[F, _]) => builder.build(f.field).append("::" + f.decoder.dbType.sqlName))

  implicit def fieldExprSoftCast[F <: Field[_], P <: HList](implicit builder: QueryFragmentBuilder[FT_FieldExprList, F, P]): QueryFragmentBuilder[FT_FieldExprList, SoftCast[F, _], P] =
    instance((f: SoftCast[F, _]) => builder.build(f.field))

  implicit def fieldExprAggregation[F <: Field[_], P <: HList](implicit builder: QueryFragmentBuilder[FT_FieldExprList, F, P]): QueryFragmentBuilder[FT_FieldExprList, Aggregation[F, _], P] =
    instance((f: Aggregation[F, _]) => builder.build(f.field).wrap(f.dbFunction.name + "(", ")"))

  implicit def fieldExpr1[F <: Field[_], P <: HList](implicit builder: QueryFragmentBuilder[FT_FieldExprList, F, P]): QueryFragmentBuilder[FT_FieldExprList, FieldExpr1[F, _], P] =
    instance((f: FieldExpr1[F, _]) => builder.build(f.field).wrap(f.dbFunction.name + "(", ")"))

  implicit def fieldExpr2[F1 <: Field[_], F2 <: Field[_], P <: HList, Q <: HList, R <: HList]
      (implicit
       builder1: QueryFragmentBuilder[FT_FieldExprList, F1, P],
       builder2: QueryFragmentBuilder[FT_FieldExprList, F2, Q],
       concat: Concat.Aux[P, Q, R]): QueryFragmentBuilder[FT_FieldExprList, FieldExpr2[F1, F2, _], R] =
    instance((f: FieldExpr2[F1, F2, _]) => (builder1.build(f.field1) `+,+` builder2.build(f.field2)).wrap(f.dbFunction.name + "(", ")"))

  implicit def placeholder[P <: NamedPlaceholder[_]]: QueryFragmentBuilder[FT_FieldExprList, P, P :: HNil] =
    instance((f: P) => QueryFragment(s"?::${f.encoder.dbType.sqlName}", f))

  implicit def value[V <: PlaceholderValue[_]]: QueryFragmentBuilder[FT_FieldExprList, V, V :: HNil] =
    instance((f: V) => QueryFragment(s"?::${f.encoder.dbType.sqlName}", f))

  implicit def optionalValue[V <: PlaceholderValueOption[_]]: QueryFragmentBuilder[FT_FieldExprList, V, V :: HNil] =
    instance((f: V) => QueryFragment(f.value.map(_ => s"?::${f.encoder.dbType.sqlName}"), f :: HNil))

  // ------------------------------
  // Column name and placeholder
  // ------------------------------

  implicit val columnName: QueryFragmentBuilder[FT_ColumnNameList, Column[_], HNil] =
    instance((f: Column[_]) => QueryFragment(f.name))

  implicit def emptyColumnNames: QueryFragmentBuilder[FT_ColumnNameList, HNil, HNil] =
    QueryFragmentBuilder.instance(_ => QueryFragment.empty)

  implicit def nonEmptyColumnNames[H, T <: HList, P <: HList, Q <: HList, R <: HList]
      (implicit
       head: QueryFragmentBuilder[FT_ColumnNameList, H, P],
       tail: QueryFragmentBuilder[FT_ColumnNameList, T, Q],
       concat: Concat.Aux[P, Q, R]): QueryFragmentBuilder[FT_ColumnNameList, H :: T, R] =
    instance { sources =>
      head.build(sources.head) `+,+` tail.build(sources.tail)
    }

  implicit def columnPlaceholder[A <: Tag, T, PL <: HList]
      (implicit
       tag: ValueOf[A],
       fieldDecoder: FieldDecoder[T],
       fieldEncoder: FieldEncoder[T],
       queryFragmentBuilder: QueryFragmentBuilder[FT_FieldExprList, NamedPlaceholder[T] with Tagging[A], PL]
      ): QueryFragmentBuilder[FT_PlaceholderList, Column[T] with Tagging[A], PL] =
    instance((_: Column[T] with Tagging[A]) => queryFragmentBuilder.build(NamedPlaceholder[T, A]))

  implicit def emptyColumnPlaceholders: QueryFragmentBuilder[FT_PlaceholderList, HNil, HNil] =
    QueryFragmentBuilder.instance(_ => QueryFragment.empty)

  implicit def nonEmptyColumnPlaceholders[H, T <: HList, P <: HList, Q <: HList, R <: HList]
      (implicit
       head: QueryFragmentBuilder[FT_PlaceholderList, H, P],
       tail: QueryFragmentBuilder[FT_PlaceholderList, T, Q],
       concat: Concat.Aux[P, Q, R]): QueryFragmentBuilder[FT_PlaceholderList, H :: T, R] =
    instance { sources =>
      head.build(sources.head) `+,+` tail.build(sources.tail)
    }

  implicit def columnNameAndPlaceholder[A <: Tag, T, PL <: HList]
      (implicit
       tag: ValueOf[A],
       fieldDecoder: FieldDecoder[T],
       fieldEncoder: FieldEncoder[T],
       queryFragmentBuilder: QueryFragmentBuilder[FT_FieldExprList, NamedPlaceholder[T] with Tagging[A], PL]
      ): QueryFragmentBuilder[FT_ColumnNameAndPlaceholderList, Column[T] with Tagging[A], PL] =
    instance((_: Column[T] with Tagging[A]) =>
      queryFragmentBuilder.build(NamedPlaceholder[T, A])
        .prepend(tag.value + " = "))

  implicit def emptyColumnNameAndPlaceholders: QueryFragmentBuilder[FT_ColumnNameAndPlaceholderList, HNil, HNil] =
    QueryFragmentBuilder.instance(_ => QueryFragment.empty)

  implicit def nonEmptyColumnNameAndPlaceholders[H, T <: HList, P <: HList, Q <: HList, R <: HList]
      (implicit
       head: QueryFragmentBuilder[FT_ColumnNameAndPlaceholderList, H, P],
       tail: QueryFragmentBuilder[FT_ColumnNameAndPlaceholderList, T, Q],
       concat: Concat.Aux[P, Q, R]): QueryFragmentBuilder[FT_ColumnNameAndPlaceholderList, H :: T, R] =
    instance { sources =>
      head.build(sources.head) `+,+` tail.build(sources.tail)
    }

  // ------------------------------
  // Binary expressions
  // ------------------------------

  implicit def whereAlwaysTrue: QueryFragmentBuilder[FT_Where, AlwaysTrue, HNil] =
    instance((_: AlwaysTrue) => QueryFragment.empty)

  implicit def whereBinaryExpr1[E1 <: BinaryExpr, P <: HList]
      (implicit left: QueryFragmentBuilder[FT_Where, E1, P]): QueryFragmentBuilder[FT_Where, BinaryExpr1[E1], P] =
    instance { (expr: BinaryExpr1[E1]) =>
      val e1 = left.build(expr.expr)
      expr match {
        case _: IsDefined[_] => e1 ++ " IS NOT NULL"
        case _: IsNotDefined[_] => e1 ++ " IS NULL"
      }
    }

  implicit def whereBinaryExpr2[E1, E2, P <: HList, Q <: HList, R <: HList]
      (implicit
       left: QueryFragmentBuilder[FT_Where, E1, P],
       right: QueryFragmentBuilder[FT_Where, E2, Q],
       concat: Concat.Aux[P, Q, R]): QueryFragmentBuilder[FT_Where, BinaryExpr2[E1, E2], R] =
    instance { expr: BinaryExpr2[E1, E2] =>
      val e1 = left.build(expr.left)
      val e2 = right.build(expr.right)
      expr match {
        case _: And[_, _] => e1.concatenateOptional(e2, " AND ")
        case _: Or[_, _] => e1.concatenateOptional(e2, " OR ")
        case _: Equals[_, _] => e1.concatenateRequired(e2, " = ")
        case _: NotEquals[_, _] => e1.concatenateRequired(e2, " != ")
        case _: GreaterThan[_, _] => e1.concatenateRequired(e2, " > ")
        case _: LessThan[_, _] => e1.concatenateRequired(e2, " < ")
        case _: GreaterThanOrEqual[_, _] => e1.concatenateRequired(e2, " >= ")
        case _: LessThanOrEqual[_, _] => e1.concatenateRequired(e2, " <= ")
        case _: Like[_, _] => e1.concatenateRequired(e2, " LIKE ")
        case _: IsSubset[_, _] => e1.concatenateRequired(e2, " <@ ")
        case _: IsSuperset[_, _] => e1.concatenateRequired(e2, " @> ")
        case _: Overlaps[_, _] => e1.concatenateRequired(e2, " && ")
        case _: IsIncluded[_, _] => e1.concatenateRequired(e2.wrap("(", ")"), " = ANY")
        case _: InSubquery[_, _, _, _, _, _, _] => e1.concatenateRequired(e2.wrap("(", ")"), " IN ")
      }
    }

  implicit def whereField[P <: HList, F <: Field[_]](implicit field: QueryFragmentBuilder[FT_FieldExprList, F, P]): QueryFragmentBuilder[FT_Where, F, P] =
    instance(field.build)

  implicit def whereSubQuery[P <: HList, S <: Select[_, _, _, _, _, _]](implicit subQuery: QueryBuilder[S, P, _]): QueryFragmentBuilder[FT_Where, S, P] =
    instance(s => QueryFragment(subQuery.build(s)))
}
