package io.epifab.yadl.postgres

import java.sql.{Connection, PreparedStatement, ResultSet, SQLException}

import cats.Id
import io.epifab.yadl._
import io.epifab.yadl.domain._

import scala.concurrent.{ExecutionContext, Future, blocking}


trait JDBCQueryRunner {
  protected def connection: Connection

  protected def extractField[T, U](resultSet: ResultSet, index: Int, fieldAdapter: FieldAdapter[T, U]): Either[ExtractorError, T] = {
    val u: U = fieldAdapter.dbType match {
      case DbType.StringDbType =>
        resultSet.getString(index).asInstanceOf[U]
      case DbType.IntDbType =>
        resultSet.getInt(index).asInstanceOf[U]
      case DbType.ArrayDbType =>
        resultSet.getArray(index).asInstanceOf[U]
    }

    fieldAdapter.extract(u)
  }

  protected def extractResults[T](select: Select, extractor: Extractor[T])(resultSet: ResultSet): Either[ExtractorError, Seq[T]] = {
    import io.epifab.yadl.utils.EitherSupport._

    val fieldIndexes: Map[Field[_, _], Int] =
      select.fields.zipWithIndex.toMap

    val results = scala.collection.mutable.ArrayBuffer.empty[Either[ExtractorError, T]]

    while (resultSet.next()) {
      val row = new Row {
        override def get[FT](field: Field[FT, _]): Either[ExtractorError, FT] =
          fieldIndexes.get(field) match {
            case Some(index) =>
              extractField(resultSet, index + 1, field.adapter)
            case None =>
              Left(ExtractorError(s"Field ${field.src} is missing"))
          }
      }
      results += extractor(row)
    }

    firstLeftOrRights(results)
  }

  protected def preparedStatement(query: Query): PreparedStatement = {
    val statement: PreparedStatement = connection
      .prepareStatement(query.query)

    query.params.zipWithIndex.foreach {
      case (param, index) => statement.setObject(index + 1, param)
    }

    statement
  }
}


class PostgresQueryRunner(protected val connection: Connection, queryBuilder: QueryBuilder[Statement]) extends QueryRunner[Id] with JDBCQueryRunner {
  override def run[T](select: Select)(implicit extractor: Row => Either[ExtractorError, T]): Id[Either[DALError, Seq[T]]] = {
    val statement = preparedStatement(queryBuilder(select))

    try {
      statement.execute()
      extractResults(select, extractor)(statement.executeQuery())
    }
    catch {
      case error: SQLException => Left(DriverError(error))
    }
  }

  override def run(update: Statement with SideEffect): Id[Either[DALError, Int]] = {
    val statement = preparedStatement(queryBuilder(update))

    try {
      statement.execute()
      Right(statement.getUpdateCount)
    }
    catch {
      case error: SQLException => Left(DriverError(error))
    }
  }
}


class AsyncPostgresQueryRunner(protected val connection: Connection, queryBuilder: QueryBuilder[Statement])(implicit executionContext: ExecutionContext) extends QueryRunner[Future] with JDBCQueryRunner {
  override def run[T](select: Select)(implicit extractor: Row => Either[ExtractorError, T]): Future[Either[DALError, Seq[T]]] = {
    val statement = preparedStatement(queryBuilder(select))

    Future {
      try {
        blocking(statement.execute())
        extractResults(select, extractor)(statement.executeQuery())
      }
      catch {
        case error: SQLException => Left(DriverError(error))
      }
    }
  }

  override def run(update: Statement with SideEffect): Future[Either[DALError, Int]] = {
    val statement = preparedStatement(queryBuilder(update))

    Future {
      try {
        blocking(statement.execute())
        Right(statement.getUpdateCount)
      }
      catch {
        case error: SQLException => Left(DriverError(error))
      }
    }
  }
}
