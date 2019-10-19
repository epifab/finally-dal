package io.epifab.yadl.typesafe

import io.epifab.yadl.typesafe.fields._
import io.epifab.yadl.typesafe.utils.{Appender, TaggedFinder}
import shapeless.{::, Generic, HList, HNil, the}

trait DataSource[FIELDS] { self: Tag[_] =>
  def fields: FIELDS

  def on[E <: BinaryExpr](clause: this.type => E): Join[this.type, E] =
    new Join(this, clause(this))
}

trait FindContext[HAYSTACK] {
  def apply[TAG <: String with Singleton, X](tag: TAG)(implicit finder: TaggedFinder[TAG, X, HAYSTACK]): X with Tag[TAG]
}

class TableBuilder[NAME <: String, SCHEMA](implicit name: ValueOf[NAME]) {
  def as[ALIAS <: Singleton with String, REPR](alias: ALIAS)(implicit a: ValueOf[ALIAS], generic: Generic.Aux[SCHEMA, REPR], columnsBuilder: ColumnsBuilder[REPR]): Table[NAME, SCHEMA] with Tag[ALIAS] =
    Table(name.value, generic.from(columnsBuilder.build(alias)), alias)
}

class Table[NAME <: String, FIELDS] private(val tableName: String, override val fields: FIELDS) extends DataSource[FIELDS] with FindContext[FIELDS] { self: Tag[_] =>
  override def apply[TAG <: String with Singleton, X](tag: TAG)(implicit finder: TaggedFinder[TAG, X, FIELDS]): X with Tag[TAG] =
    finder.find(fields)
}

object Table {
  def unapply(table: Table[_, _]): Option[String] = Some(table.tableName)

  protected[typesafe] def apply[NAME <: String, FIELDS, ALIAS <: String](tableName: String, fields: FIELDS, tableAlias: String): Table[NAME, FIELDS] with Tag[ALIAS] = new Table[NAME, FIELDS](tableName, fields) with Tag[ALIAS] {
    override def tagValue: String = tableAlias
  }
}

class SubQuery[SUBQUERY_FIELDS <: HList, S <: Select[_, _, _, _]]
    (val select: S, override val fields: SUBQUERY_FIELDS)
    extends DataSource[SUBQUERY_FIELDS] with FindContext[SUBQUERY_FIELDS] { self: Tag[_] =>
  override def apply[TAG <: String with Singleton, X](tag: TAG)(implicit finder: TaggedFinder[TAG, X, SUBQUERY_FIELDS]): X with Tag[TAG] =
    finder.find(fields)
}

trait SubQueryFields[-FIELDS, +SUBQUERY_FIELDS] {
  def build(srcAlias: String, fields: FIELDS): SUBQUERY_FIELDS
}

object SubQueryFields {
  implicit def singleField[T, ALIAS <: String]: SubQueryFields[Field[T] with Tag[ALIAS], Column[T] with Tag[ALIAS]] =
    (srcAlias: String, field: Field[T] with Tag[ALIAS]) => new Column(field.tagValue, srcAlias)(field.decoder) with Tag[ALIAS] {
      override def tagValue: String = field.tagValue
    }

  implicit def hNil: SubQueryFields[HNil, HNil] =
    (_: String, _: HNil) => HNil

  implicit def hCons[H, RH, T <: HList, RT <: HList](implicit headField: SubQueryFields[H, RH], tailFields: SubQueryFields[T, RT]): SubQueryFields[H :: T, RH :: RT] =
    (srcAlias: String, list: H :: T) => headField.build(srcAlias, list.head) :: tailFields.build(srcAlias, list.tail)
}

class Join[+DS <: DataSource[_], +E <: BinaryExpr](val dataSource: DS, val filter: E)

trait SelectContext[FIELDS <: HList, SOURCES <: HList] extends FindContext[(FIELDS, SOURCES)] {
  def fields: FIELDS
  def sources: SOURCES

  override def apply[TAG <: String with Singleton, X](tag: TAG)(implicit finder: TaggedFinder[TAG, X, (FIELDS, SOURCES)]): X with Tag[TAG] =
    finder.find((fields, sources))

  def apply[TAG1 <: String with Singleton, TAG2 <: String with Singleton, X2, HAYSTACK2]
      (tag1: TAG1, tag2: TAG2)
      (implicit
       finder1: TaggedFinder[TAG1, FindContext[HAYSTACK2], SOURCES],
       finder2: TaggedFinder[TAG2, X2, HAYSTACK2]): X2 AS TAG2 =
    finder1.find(sources).apply[TAG2, X2](tag2)
}

sealed trait Select[FIELDS <: HList, GROUP_BY <: HList, SOURCES <: HList, WHERE <: BinaryExpr] extends SelectContext[FIELDS, SOURCES] { select =>
  def queryBuilder: QueryBuilder[this.type]

  def fields: FIELDS
  def groupByFields: GROUP_BY
  def sources: SOURCES
  def filter: WHERE

  class SubQueryBuilder[SUBQUERY_FIELDS <: HList](implicit val refinedFields: SubQueryFields[FIELDS, SUBQUERY_FIELDS]) {
    def as[ALIAS <: String](implicit alias: ValueOf[ALIAS]): SubQuery[SUBQUERY_FIELDS, Select[FIELDS, GROUP_BY, SOURCES, WHERE]] with Tag[ALIAS] =
      new SubQuery(select, refinedFields.build(alias.value, select.fields)) with Tag[ALIAS] {
        override def tagValue: String = alias.value
      }
  }

  def subQuery[SUBQUERY_FIELDS <: HList](implicit subQueryFields: SubQueryFields[FIELDS, SUBQUERY_FIELDS]) =
    new SubQueryBuilder[SUBQUERY_FIELDS]

  lazy val query: Query = queryBuilder.build(this)

  def placeholders[PLACEHOLDERS <: HList, UNIQUE_PLACEHOLDERS <: HList]
      (implicit
       placeholderExtractor: PlaceholderExtractor[Select[FIELDS, GROUP_BY, SOURCES, WHERE], PLACEHOLDERS],
       hSet: HSet[PLACEHOLDERS, UNIQUE_PLACEHOLDERS]): UNIQUE_PLACEHOLDERS =
    hSet.toSet(placeholderExtractor.extract(this))
}

trait EmptySelect extends Select[HNil, HNil, HNil, AlwaysTrue] {
  override val queryBuilder: QueryBuilder[Select[HNil, HNil, HNil, AlwaysTrue]] =
    the[QueryBuilder[Select[HNil, HNil, HNil, AlwaysTrue]]]

  override val fields: HNil = HNil
  override val groupByFields: HNil = HNil
  override val sources: HNil = HNil
  override val filter: AlwaysTrue = AlwaysTrue

  def from[T <: DataSource[_] with Tag[_]](source: T)(implicit queryBuilder: QueryBuilder[Select[HNil, HNil, T :: HNil, AlwaysTrue]]): NonEmptySelect[HNil, HNil, T :: HNil, AlwaysTrue] =
    new NonEmptySelect(fields, groupByFields, source :: HNil, filter)
}

class NonEmptySelect[FIELDS <: HList, GROUP_BY <: HList, SOURCES <: HList, WHERE <: BinaryExpr]
    (override val fields: FIELDS,
     override val groupByFields: GROUP_BY,
     override val sources: SOURCES,
     override val filter: WHERE)
    (implicit val queryBuilder: QueryBuilder[Select[FIELDS, GROUP_BY, SOURCES, WHERE]])
    extends Select[FIELDS, GROUP_BY, SOURCES, WHERE] {

  def take[NEW_FIELDS <: HList]
    (f: SelectContext[FIELDS, SOURCES] => NEW_FIELDS)
    (implicit queryBuilder: QueryBuilder[Select[NEW_FIELDS, GROUP_BY, SOURCES, WHERE]]):
    NonEmptySelect[NEW_FIELDS, GROUP_BY, SOURCES, WHERE] =
      new NonEmptySelect(f(this), groupByFields, sources, filter)

  def groupBy[NEW_GROUPBY <: HList]
    (f: SelectContext[FIELDS, SOURCES] => NEW_GROUPBY)
    (implicit queryBuilder: QueryBuilder[Select[FIELDS, NEW_GROUPBY, SOURCES, WHERE]]):
    NonEmptySelect[FIELDS, NEW_GROUPBY, SOURCES, WHERE] =
      new NonEmptySelect(fields, f(this), sources, filter)

  def join[NEW_SOURCE <: DataSource[_] with Tag[_], JOIN_CLAUSE <: BinaryExpr, SOURCE_RESULTS <: HList]
    (f: SelectContext[FIELDS, SOURCES] => Join[NEW_SOURCE, JOIN_CLAUSE])
    (implicit
     appender: Appender.Aux[SOURCES, Join[NEW_SOURCE, JOIN_CLAUSE], SOURCE_RESULTS],
     queryBuilder: QueryBuilder[Select[FIELDS, GROUP_BY, SOURCE_RESULTS, WHERE]]):
    NonEmptySelect[FIELDS, GROUP_BY, SOURCE_RESULTS, WHERE] =
      new NonEmptySelect(fields, groupByFields, appender.append(sources, f(this)), filter)

  def where[NEW_WHERE <: BinaryExpr]
    (f: SelectContext[FIELDS, SOURCES] => NEW_WHERE)
    (implicit queryBuilder: QueryBuilder[Select[FIELDS, GROUP_BY, SOURCES, NEW_WHERE]]): NonEmptySelect[FIELDS, GROUP_BY, SOURCES, NEW_WHERE] =
    new NonEmptySelect(fields, groupByFields, sources, f(this))
}

object Select extends EmptySelect
