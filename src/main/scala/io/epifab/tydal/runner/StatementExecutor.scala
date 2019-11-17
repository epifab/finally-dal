package io.epifab.tydal.runner

import java.sql.{Connection, PreparedStatement, ResultSet}

import cats.Monad
import cats.data.EitherT
import io.epifab.tydal._
import shapeless.{HList, HNil}

import scala.util.Try
import scala.util.control.NonFatal

trait StatementExecutor[CONN, Fields <: HList, Output] {
  def run[F[+_]: Eff: Monad](connection: CONN, statement: RunnableStatement[Fields]): F[Either[DataError, Output]]
}

trait ReadStatementExecutor[CONN, Fields <: HList, ROW]
  extends StatementExecutor[CONN, Fields, Iterator[Either[DecoderError, ROW]]]

trait WriteStatementExecutor[CONN, Fields <: HList]
  extends StatementExecutor[CONN, Fields, Int]


object ReadStatementExecutor {
  implicit def jdbcQuery[Fields <: HList, ROW]
  (implicit dataExtractor: DataExtractor[ResultSet, Fields, ROW]): ReadStatementExecutor[Connection, Fields, ROW] =

    new ReadStatementExecutor[Connection, Fields, ROW] {
      override def run[F[+_]: Eff: Monad](connection: Connection, statement: RunnableStatement[Fields]): F[Either[DataError, Iterator[Either[DecoderError, ROW]]]] =
        (for {
          preparedStatement <- EitherT(Eff[F].delay(Jdbc.initStatement(connection, statement.sql, statement.input)))
          results <- EitherT(Eff[F].delay(runStatement(preparedStatement, statement.fields)))
        } yield results).value

      private def runStatement(preparedStatement: PreparedStatement, fields: Fields): Either[DataError, Iterator[Either[DecoderError, ROW]]] =
        Try(preparedStatement.executeQuery()).toEither match {
          case Right(resultSet) => Right(extract(fields, preparedStatement, resultSet))
          case Left(NonFatal(e)) => Left(DriverError(e.getMessage))
          case Left(fatalError) => throw fatalError
        }

      private def extract(fields: Fields, preparedStatement: PreparedStatement, resultSet: ResultSet): Iterator[Either[DecoderError, ROW]] = {
        new Iterator[Either[DecoderError, ROW]] {
          override def hasNext: Boolean = {
            if (!resultSet.next()) {
              try { resultSet.close(); preparedStatement.close(); false }
              catch { case NonFatal(_) => false }
            }
            else true
          }
          override def next(): Either[DecoderError, ROW] = dataExtractor.extract(resultSet, fields)
        }
      }
    }
}

object StatementExecutor {
  implicit def jdbcUpdate: WriteStatementExecutor[Connection, HNil] =
    new WriteStatementExecutor[Connection, HNil] {
      override def run[F[+_]: Eff: Monad](connection: Connection, statement: RunnableStatement[HNil]): F[Either[DataError, Int]] =
        (for {
          preparedStatement <- EitherT(Eff[F].delay(Jdbc.initStatement(connection, statement.sql, statement.input)))
          results <- EitherT(Eff[F].delay(runStatement(preparedStatement)))
        } yield results).value

      private def runStatement(preparedStatement: PreparedStatement): Either[DataError, Int] =
        Try(preparedStatement.executeUpdate()).toEither match {
          case Right(updatedRows) => Right(updatedRows)
          case Left(NonFatal(e)) => Left(DriverError(e.getMessage))
          case Left(fatalError) => throw fatalError
        }
    }
}
