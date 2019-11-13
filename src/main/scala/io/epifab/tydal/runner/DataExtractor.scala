package io.epifab.tydal.runner

import java.sql.ResultSet

import io.epifab.tydal.Tagging
import io.epifab.tydal.fields._
import shapeless.{::, HList, HNil}

import scala.util.Try

/**
 * Type class to extract data from a generic result set
 *
 * @tparam RS Result set
 * @tparam FIELDS Fields definitions
 * @tparam OUTPUT Data output
 */
trait DataExtractor[RS, FIELDS, OUTPUT] {
  def extract(resultSet: RS, fields: FIELDS): Either[DecoderError, OUTPUT]
}

object DataExtractor {
  class Extractor[RESULTS, FIELDS](resultSet: RESULTS, fields: FIELDS) {
    def as[OUTPUT](implicit dataExtractor: DataExtractor[RESULTS, FIELDS, OUTPUT]): Either[DecoderError, OUTPUT] =
      dataExtractor.extract(resultSet, fields)
  }

  def apply[RESULTS, FIELDS](resultSet: RESULTS, fields: FIELDS): Extractor[RESULTS, FIELDS] =
    new Extractor(resultSet, fields)

  implicit def jdbcField[F <: Field[_] with Tagging[_], T](implicit fieldT: FieldT[F, T]): DataExtractor[ResultSet, F, T] =
    new DataExtractor[ResultSet, F, T] {
      def getSeq[U](resultSet: ResultSet, dbType: FieldType[Any], fieldName: String): Seq[Any] =
        resultSet
          .getArray(fieldName)
          .getArray
          .asInstanceOf[Array[U]]
          .toSeq

      def get[U](resultSet: ResultSet, dbType: FieldType[Any], fieldName: String): Any = {
        dbType match {
          case TypeString | TypeDate | TypeDateTime | TypeJson | TypeEnum(_) | TypeGeography | TypeGeometry =>
            resultSet.getObject(fieldName).toString

          case TypeInt =>
            resultSet.getInt(fieldName)

          case TypeDouble =>
            resultSet.getDouble(fieldName)

          case TypeSeq(innerType) =>
            getSeq(resultSet, innerType, fieldName)

          case TypeOption(innerDbType) =>
            Try(Option(resultSet.getObject(fieldName))).toOption.flatten
              .map(_ => get(resultSet, innerDbType, fieldName))
        }
      }

      override def extract(resultSet: ResultSet, field: F): Either[DecoderError, T] = {
        val decoder: FieldDecoder[T] = fieldT.get(field).decoder
        decoder.decode(get(resultSet, decoder.dbType, field.tagValue).asInstanceOf[decoder.DBTYPE])
      }
    }

  implicit def hNil[RS]: DataExtractor[RS, HNil, HNil] =
    (_: RS, _: HNil) => Right(HNil)

  implicit def hCons[RS, H, HX, T <: HList, TX <: HList]
      (implicit
       head: DataExtractor[RS, H, HX],
       tail: DataExtractor[RS, T, TX]): DataExtractor[RS, H :: T, HX :: TX] =
    (resultSet: RS, fields: H :: T) => for {
      h <- head.extract(resultSet, fields.head)
      t <- tail.extract(resultSet, fields.tail)
    } yield h :: t
}
