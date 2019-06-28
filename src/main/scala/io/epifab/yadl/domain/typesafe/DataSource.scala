package io.epifab.yadl.domain.typesafe

import io.epifab.yadl.domain.typesafe.DataSource.DataSourceFinder
import io.epifab.yadl.domain.FieldAdapter
import io.epifab.yadl.utils.{Appender, Finder}
import shapeless.{::, HList, HNil}

object DataSource {
  trait DataSourceFinder[X, U] {
    def find(u: U): X
  }

  object DataSourceFinder {
    implicit def joinedFinder[X <: DataSource[_], T <: HList]: DataSourceFinder[X, Join[X] :: T] =
      (u: Join[X] :: T) => u.head.dataSource

    implicit def headFinder[X, T <: HList]: DataSourceFinder[X, X :: T] =
      (u: X :: T) => u.head

    implicit def tailFinder[X, H, T <: HList](implicit finder: DataSourceFinder[X, T]): DataSourceFinder[X, H :: T] =
      (u: H :: T) => finder.find(u.tail)
  }
}

trait DataSource[TERMS <: HList] extends Taggable {
  def `*`: TERMS

  def on(clause: this.type => BinaryExpr): Join[this.type] =
    new Join(this, clause(this))
}

abstract class Table[TERMS <: HList](val tableName: String) extends DataSource[TERMS] {
  def term[T](name: String)(implicit fieldAdapter: FieldAdapter[T]): Term[T] =
    new Column[T](name, this)
}

class Join[+DS <: DataSource[_]](val dataSource: DS, filter: BinaryExpr)

trait SelectContext[PLACEHOLDERS <: HList, SOURCES <: HList] {
  def placeholders: PLACEHOLDERS
  def sources: SOURCES

  def dataSource[X](implicit dataSourceFinder: DataSourceFinder[X, SOURCES]): X =
    dataSourceFinder.find(sources)

  def placeholder[X, A](implicit placeHolderFinder: Finder[Placeholder[X] with Alias[A], PLACEHOLDERS]): Placeholder[X] with Alias[A] =
    placeHolderFinder.find(placeholders)
}

sealed trait Select[PLACEHOLDERS <: HList, TERMS <: HList, SOURCES <: HList]
    extends SelectContext[PLACEHOLDERS, SOURCES] with DataSource[TERMS] {
  def placeholders: PLACEHOLDERS
  def terms: TERMS
  def sources: SOURCES

  def `*`: TERMS = terms
}

trait EmptySelect extends Select[HNil, HNil, HNil] {
  override def placeholders: HNil = HNil
  override def terms: HNil = HNil
  override def sources: HNil = HNil

  def from[T <: DataSource[_] with Alias[_]](source: T): NonEmptySelect[HNil, HNil, T :: HNil] =
    new NonEmptySelect(HNil, terms, source :: HNil)
}

class NonEmptySelect[PLACEHOLDERS <: HList, TERMS <: HList, SOURCES <: HList]
    (val placeholders: PLACEHOLDERS, val terms: TERMS, val sources: SOURCES, val where: BinaryExpr = BinaryExpr.empty) extends Select[PLACEHOLDERS, TERMS, SOURCES] {

  def take[NEW_TERMS <: HList]
    (f: SelectContext[PLACEHOLDERS, SOURCES] => NEW_TERMS):
    NonEmptySelect[PLACEHOLDERS, NEW_TERMS, SOURCES] =
      new NonEmptySelect(placeholders, f(this), sources)

  def join[NEW_SOURCE <: DataSource[_] with Alias[_], SOURCE_RESULTS <: HList]
    (f: SelectContext[PLACEHOLDERS, SOURCES] => Join[NEW_SOURCE])
    (implicit appender: Appender.Aux[SOURCES, Join[NEW_SOURCE], SOURCE_RESULTS]):
    NonEmptySelect[PLACEHOLDERS, TERMS, SOURCE_RESULTS] =
      new NonEmptySelect(placeholders, terms, appender.append(sources, f(this)))

  def withPlaceholder[T, U](implicit fieldAdapter: FieldAdapter[T]): NonEmptySelect[(Placeholder[T] with Alias[U]) :: PLACEHOLDERS, TERMS, SOURCES] =
    new NonEmptySelect(new Placeholder[T].as[U] :: placeholders, terms, sources)

  def where(f: SelectContext[PLACEHOLDERS, SOURCES] => BinaryExpr): NonEmptySelect[PLACEHOLDERS, TERMS, SOURCES] =
    new NonEmptySelect(placeholders, terms, sources, where and f(this))
}

object Select extends EmptySelect
