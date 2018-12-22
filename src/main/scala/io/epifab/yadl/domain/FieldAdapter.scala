package io.epifab.yadl.domain

import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalDateTime}

import io.circe.{Decoder, Encoder, Printer}

import scala.util.Try

trait FieldAdapter[T] {
  type DBTYPE
  def dbType: DbType[DBTYPE]
  def toDb(value: T): DBTYPE
  def fromDb(dbValue: DBTYPE): Either[ExtractorError, T]
}

trait PrimitiveFieldAdapter[T] extends FieldAdapter[T] { outer =>
  override def dbType: PrimitiveDbType[DBTYPE]

  def bimap[U](f: T => Either[ExtractorError, U], g: U => T): PrimitiveFieldAdapter[U] =
    new PrimitiveFieldAdapter[U] {
      override type DBTYPE = outer.DBTYPE
      override def dbType: PrimitiveDbType[outer.DBTYPE] = outer.dbType
      override def toDb(value: U): outer.DBTYPE = outer.toDb(g(value))
      override def fromDb(dbValue: outer.DBTYPE): Either[ExtractorError, U] = for {
        t <- outer.fromDb(dbValue)
        u <- f(t)
      } yield u
    }

  def bimap[U](f: T => U, g: U => T)(implicit d1: DummyImplicit): PrimitiveFieldAdapter[U] = {
    bimap[U](
      (dbValue: T) => Try(f(dbValue))
        .toEither
        .left.map(error => ExtractorError(error.getMessage)),
      g
    )
  }

  def bimap[U](f: T => Option[U], g: U => T)(implicit d1: DummyImplicit, d2: DummyImplicit): PrimitiveFieldAdapter[U] = {
    bimap[U](
      (dbValue: T) => f(dbValue) match {
        case Some(value) => Right(value)
        case None => Left(ExtractorError(s"Could not decode $dbValue"))
      },
      g
    )
  }
}

abstract class SimpleFieldAdapter[T](override val dbType: PrimitiveDbType[T]) extends PrimitiveFieldAdapter[T] {
  type DBTYPE = T
  def toDb(value: T): DBTYPE = value
  def fromDb(dbValue: DBTYPE): Either[ExtractorError, T] = Right(dbValue)
}

case object StringFieldAdapter extends SimpleFieldAdapter[String](StringDbType)

case object IntFieldAdapter extends SimpleFieldAdapter[Int](IntDbType)

case object DoubleFieldAdapter extends SimpleFieldAdapter[Double](DoubleDbType)

case class OptionFieldAdapter[T, U](override val dbType: DbType[Option[U]], baseAdapter: FieldAdapter.Aux[T, U])
  extends FieldAdapter[Option[T]] {
  override type DBTYPE = Option[baseAdapter.DBTYPE]

  override def toDb(value: Option[T]): DBTYPE = value.map(baseAdapter.toDb)

  override def fromDb(dbValue: DBTYPE): Either[ExtractorError, Option[T]] = dbValue match {
    case None => Right(None)
    case Some(u) => baseAdapter.fromDb(u).map(Some(_))
  }
}

case class SeqFieldAdapter[T, U](override val dbType: PrimitiveDbType[Seq[U]], baseAdapter: FieldAdapter.Aux[T, U])
  extends PrimitiveFieldAdapter[Seq[T]] {
  import io.epifab.yadl.utils.EitherSupport._

  override type DBTYPE = Seq[U]

  override def toDb(value: Seq[T]): Seq[baseAdapter.DBTYPE] = value.map(baseAdapter.toDb)

  override def fromDb(dbValue: Seq[baseAdapter.DBTYPE]): Either[ExtractorError, Seq[T]] =
    firstLeftOrRights(dbValue.map(baseAdapter.fromDb))
}

class JsonFieldAdapter[T](implicit decoder: Decoder[T], encoder: Encoder[T]) extends FieldAdapter[T] {
  import io.circe.parser.decode
  import io.circe.syntax._

  override type DBTYPE = String

  override def dbType: DbType[String] = JsonDbType

  override def toDb(value: T): String =
    value.asJson.noSpaces

  override def fromDb(dbValue: String): Either[ExtractorError, T] =
    decode[T](dbValue)
      .left.map(error => ExtractorError(error.getMessage))
}

class EnumFieldAdapter(name: String) extends PrimitiveFieldAdapter[String] {
  override type DBTYPE = String
  override def dbType: EnumDbType = EnumDbType(name)
  override def toDb(value: String): String = value
  override def fromDb(dbValue: String): Either[ExtractorError, String] = Right(dbValue)
}

class DateFieldAdapter extends PrimitiveFieldAdapter[LocalDate] {
  override type DBTYPE = String
  override def dbType: DateDbType.type = DateDbType

  override def toDb(value: LocalDate): String =
    value.format(DateTimeFormatter.ISO_DATE)

  override def fromDb(dbValue: String): Either[ExtractorError, LocalDate] =
    Try(LocalDate.parse(dbValue, DateTimeFormatter.ofPattern("yyyy-MM-dd")))
      .toEither
      .left.map(error => ExtractorError(error.getMessage))
}

class DateTimeFieldAdapter extends PrimitiveFieldAdapter[LocalDateTime] {
  override type DBTYPE = String
  override def dbType: PrimitiveDbType[String] = DateTimeDbType

  override def toDb(value: LocalDateTime): String =
    value.format(DateTimeFormatter.ISO_DATE_TIME)

  override def fromDb(dbValue: String): Either[ExtractorError, LocalDateTime] =
    Try(LocalDateTime.parse(dbValue, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S")))
      .toEither
      .left.map(error => ExtractorError(error.getMessage))
}

object StringSeqFieldAdapter extends SeqFieldAdapter[String, String](StringSeqDbType, StringFieldAdapter)
object IntSeqFieldAdapter extends SeqFieldAdapter[Int, Int](IntSeqDbType, IntFieldAdapter)
object DoubleSeqFieldAdapter extends SeqFieldAdapter[Double, Double](DoubleSeqDbType, DoubleFieldAdapter)

object FieldAdapter {
  type Aux[T, U] = FieldAdapter[T] { type DBTYPE = U }

  implicit val string: PrimitiveFieldAdapter[String] = StringFieldAdapter
  implicit val int: PrimitiveFieldAdapter[Int] = IntFieldAdapter
  implicit val double: PrimitiveFieldAdapter[Double] = DoubleFieldAdapter
  implicit val stringSeq: PrimitiveFieldAdapter[Seq[String]] = StringSeqFieldAdapter
  implicit val intSeq: PrimitiveFieldAdapter[Seq[Int]] = IntSeqFieldAdapter
  implicit val doubleSeq: PrimitiveFieldAdapter[Seq[Double]] = DoubleSeqFieldAdapter

  implicit def option[T](implicit baseAdapter: PrimitiveFieldAdapter[T]): FieldAdapter[Option[T]] =
    OptionFieldAdapter[T, baseAdapter.DBTYPE](OptionDbType(baseAdapter.dbType), baseAdapter)

  def json[T](implicit encoder: Encoder[T], decoder: Decoder[T]): FieldAdapter[T] =
    new JsonFieldAdapter[T]

  def enum[T](name: String, encode: T => String, decode: String => T): FieldAdapter[T] =
    new EnumFieldAdapter(name).bimap[T](decode, encode)

  implicit val date: PrimitiveFieldAdapter[LocalDate] = new DateFieldAdapter

  implicit val dateTime: PrimitiveFieldAdapter[LocalDateTime] = new DateTimeFieldAdapter
}
