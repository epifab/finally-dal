package io.epifab.yadl.postgres

import java.sql.{Connection, PreparedStatement, ResultSet, SQLException}

import cats.Id
import io.epifab.yadl.domain._
import io.epifab.yadl.utils.LoggingSupport

import scala.concurrent.{ExecutionContext, Future, blocking}


trait JDBCQueryRunner {
  protected def connection: Connection

  private def setParameter[T](statement: PreparedStatement, index: Integer, value: Value[T]): Unit = {
    def set[U](index: Int, dbValue: U, dbType: ScalarDbType[U]): Any = dbType match {
      case StringDbType | DateDbType | DateTimeDbType | JsonDbType | EnumDbType(_) =>
        statement.setObject(index, dbValue)

      case IntDbType =>
        statement.setInt(index, dbValue)

      case DoubleDbType =>
        statement.setDouble(index, dbValue)

      case PointDbType =>
        statement.setString(index, dbValue)

      case StringSeqDbType =>
        val array: java.sql.Array = connection.createArrayOf(
          "varchar",
          dbValue.toArray
        )
        statement.setArray(index, array)

      case EnumSeqDbType(enum) =>
        val array: java.sql.Array = connection.createArrayOf(
          enum.name,
          dbValue.toArray
        )
        statement.setArray(index, array)

      case DateSeqDbType =>
        val array: java.sql.Array = connection.createArrayOf(
          "date",
          dbValue.toArray
        )
        statement.setArray(index, array)

      case DateTimeSeqDbType =>
        val array: java.sql.Array = connection.createArrayOf(
          "timestamp",
          dbValue.toArray
        )
        statement.setArray(index, array)

      case IntSeqDbType =>
        val array: java.sql.Array = connection.createArrayOf(
          "integer",
          dbValue.map(java.lang.Integer.valueOf(_)).toArray
        )
        statement.setArray(index, array)

      case DoubleSeqDbType =>
        val array: java.sql.Array = connection.createArrayOf(
          "double",
          dbValue.map(java.lang.Double.valueOf(_)).toArray
        )
        statement.setArray(index, array)

      case OptionDbType(innerType) =>
        dbValue match {
          case None =>
            statement.setObject(index, null)

          case Some(innerValue) =>
            set(index, innerValue, innerType)
        }
    }

    set(index, value.dbValue, value.adapter.dbType)
  }

  private def getColumn[T](resultSet: ResultSet, index: Int)(implicit adapter: FieldAdapter[T]): Either[ExtractorError, T] = {
    def get[U](index: Int, dbType: ScalarDbType[U]): U = dbType match {
      case StringDbType | DateDbType | DateTimeDbType | JsonDbType | EnumDbType(_) =>
        resultSet.getObject(index).toString

      case IntDbType =>
        resultSet.getInt(index)

      case DoubleDbType =>
        resultSet.getDouble(index)

      case PointDbType =>
        resultSet.getString(index)

      case StringSeqDbType | EnumSeqDbType(_) | DateSeqDbType | DateTimeSeqDbType =>
        resultSet.getArray(index)
          .getArray
          .asInstanceOf[Array[String]]
          .toSeq

      case IntSeqDbType =>
        resultSet.getArray(index)
          .getArray
          .asInstanceOf[Array[Integer]]
          .toSeq
          .map(_.toInt)

      case DoubleSeqDbType =>
        resultSet.getArray(index)
          .getArray
          .asInstanceOf[Array[Double]]
          .toSeq
          .map(_.toDouble)

      case OptionDbType(innerType) =>
        Option(resultSet.getObject(index)).map(_ => get(index, innerType))
    }

    adapter.read(get(index, adapter.dbType))
  }

  protected def extractResults[T](select: Select[_], extractor: Extractor[T])(resultSet: ResultSet): Either[ExtractorError, Seq[T]] = {
    import io.epifab.yadl.utils.EitherSupport._

    val termsIndexes: Map[Term[_], Int] = select.terms.toSeq.zipWithIndex.toMap

    val results = scala.collection.mutable.ArrayBuffer.empty[Either[ExtractorError, T]]

    while (resultSet.next()) {
      val row = new Row {
        override def get[FT](term: Term[FT]): Either[ExtractorError, FT] =
          termsIndexes.get(term) match {
            case Some(index) =>
              getColumn(resultSet, index + 1)(term.adapter)
            case None =>
              Left(ExtractorError(s"Column $term is missing"))
          }
      }
      results += extractor.extract(row)
    }

    firstLeftOrRights(results)
  }

  protected def preparedStatement(query: Query): PreparedStatement = {
    val statement: PreparedStatement = connection
      .prepareStatement(query.sql)

    query.params.zipWithIndex.foreach {
      case (value, index) =>
        setParameter(statement, index + 1, value)
    }

    statement
  }
}


class SyncQueryRunner(protected val connection: Connection, queryBuilder: QueryBuilder[Statement]) extends QueryRunner[Id] with JDBCQueryRunner with LoggingSupport {
  override def run[T](select: Select[T]): Id[Either[DALError, Seq[T]]] = {
    val query = queryBuilder(select)
    val statement = preparedStatement(query)

    try {
      extractResults(select, select.terms.extractor.extract)(statement.executeQuery())
    }
    catch {
      case error: SQLException =>
        withMdc(Map("query" -> query.sql)) { log.error("Could not run SQL query", error) }
        Left(DriverError(error))
    }
  }

  override def run(update: Statement with SideEffect): Id[Either[DALError, Int]] = {
    val query = queryBuilder(update)
    val statement = preparedStatement(query)

    try {
      statement.execute()
      Right(statement.getUpdateCount)
    }
    catch {
      case error: SQLException =>
        withMdc(Map("query" -> query.sql)) { log.error("Could not run SQL query", error) }
        Left(DriverError(error))
    }
  }
}


class AsyncPostgresQueryRunner(protected val connection: Connection, queryBuilder: QueryBuilder[Statement])(implicit executionContext: ExecutionContext) extends QueryRunner[Future] with JDBCQueryRunner with LoggingSupport {
  override def run[T](select: Select[T]): Future[Either[DALError, Seq[T]]] = {
    val query = queryBuilder(select)
    val statement = preparedStatement(query)

    Future {
      try {
        extractResults(select, select.terms.extractor)(blocking(statement.executeQuery))
      }
      catch {
        case error: SQLException =>
          withMdc(Map("query" -> query.sql)) { log.error("Could not run SQL query", error) }
          Left(DriverError(error))
      }
    }
  }

  override def run(update: Statement with SideEffect): Future[Either[DALError, Int]] = {
    val query = queryBuilder(update)
    val statement = preparedStatement(query)

    Future {
      try {
        blocking(statement.execute())
        Right(statement.getUpdateCount)
      }
      catch {
        case error: SQLException =>
          withMdc(Map("query" -> query.sql)) { log.error("Could not run SQL query", error) }
          Left(DriverError(error))
      }
    }
  }
}
