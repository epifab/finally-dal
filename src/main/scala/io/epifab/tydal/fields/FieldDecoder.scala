package io.epifab.tydal.fields

import java.time.{Instant, LocalDate, LocalDateTime, ZoneOffset}

import io.circe.{Decoder => JsonDecoder}
import io.epifab.tydal.runner.{DecoderError, SqlDate, SqlDateTime}
import io.epifab.tydal.utils.EitherSupport

import scala.util.Try

trait FieldDecoder[+T] { baseDecoder =>
  type DBTYPE
  def dbType: FieldType[DBTYPE]
  def decode(value: DBTYPE): Either[DecoderError, T]

  def toSeq: FieldDecoder.Aux[Seq[T], Seq[DBTYPE]] = new FieldDecoder[Seq[T]] {
    override type DBTYPE = Seq[baseDecoder.DBTYPE]
    override def dbType: FieldType[Seq[baseDecoder.DBTYPE]] = baseDecoder.dbType.toSeq
    override def decode(value: DBTYPE): Either[DecoderError, Seq[T]] = EitherSupport.firstLeftOrRights(value.map(baseDecoder.decode))
  }

  def toOption: FieldDecoder.Aux[Option[T], Option[DBTYPE]] = new FieldDecoder[Option[T]] {
    override type DBTYPE = Option[baseDecoder.DBTYPE]
    override def dbType: FieldType[Option[baseDecoder.DBTYPE]] = baseDecoder.dbType.toOption
    override def decode(value: DBTYPE): Either[DecoderError, Option[T]] = value match {
      case None => Right(None)
      case Some(u) => baseDecoder.decode(u).map(Some(_))
    }
  }
}

object FieldDecoder {
  type Aux[+T, D] = FieldDecoder[T] { type DBTYPE = D }

  implicit val stringDecoder: FieldDecoder.Aux[String, String] = new FieldDecoder[String] {
    override type DBTYPE = String
    override def dbType: FieldType[String] = TypeString
    override def decode(value: String): Either[DecoderError, String] = Right(value)
  }

  implicit val intDecoder: FieldDecoder.Aux[Int, Int] = new FieldDecoder[Int] {
    override type DBTYPE = Int
    override def dbType: FieldType[Int] = TypeInt
    override def decode(value: Int): Either[DecoderError, Int] = Right(value)
  }

  implicit val doubleDecoder: FieldDecoder.Aux[Double, Double] = new FieldDecoder[Double] {
    override type DBTYPE = Double
    override def dbType: FieldType[Double] = TypeDouble
    override def decode(value: Double): Either[DecoderError, Double] = Right(value)
  }

  implicit val instantDecoder: FieldDecoder.Aux[Instant, String] = new FieldDecoder[Instant] {
    override type DBTYPE = String
    override def dbType: FieldType[String] = TypeDateTime
    override def decode(value: String): Either[DecoderError, Instant] =
      Try(LocalDateTime.parse(value, SqlDateTime.parser))
        .toEither
        .map(_.toInstant(ZoneOffset.UTC))
        .left.map(error => DecoderError(s"Could not parse timestamp: ${error.getMessage}"))
  }

  implicit val dateDecoder: FieldDecoder.Aux[LocalDate, String] = new FieldDecoder[LocalDate] {
    override type DBTYPE = String
    override def dbType: FieldType[String] = TypeDate
    override def decode(value: String): Either[DecoderError, LocalDate] =
      Try(LocalDate.parse(value.take(10), SqlDate.formatter))
        .toEither
        .left.map(error => DecoderError(s"Could not parse date: ${error.getMessage}"))
  }

  def jsonDecoder[A](implicit jsonDecoder: JsonDecoder[A]): FieldDecoder.Aux[A, String] = new FieldDecoder[A] {
    import io.circe.parser.{decode => decodeJson}
    override type DBTYPE = String
    override def dbType: FieldType[String] = TypeJson
    override def decode(value: String): Either[DecoderError, A] = decodeJson(value).left.map(circeError => DecoderError(circeError.getMessage))
  }

  def enumDecoder[A](sqlName: String, decodeFunction: String => Either[String, A]): FieldDecoder.Aux[A, String] = new FieldDecoder[A] {
    override type DBTYPE = String
    override def dbType: FieldType[String] = TypeEnum(sqlName)
    override def decode(value: String): Either[DecoderError, A] = decodeFunction(value).left.map(DecoderError)
  }

  implicit def seqDecoder[T, U](implicit baseDecoder: FieldDecoder.Aux[T, U]): FieldDecoder.Aux[Seq[T], Seq[U]] =
    baseDecoder.toSeq

  implicit def optionDecoder[T, U](implicit baseDecoder: FieldDecoder.Aux[T, U]): FieldDecoder.Aux[Option[T], Option[U]] =
    baseDecoder.toOption
}
