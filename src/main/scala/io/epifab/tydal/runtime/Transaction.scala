package io.epifab.tydal.runtime

import java.sql.Connection

import cats.data.EitherT
import cats.effect.{ContextShift, IO, LiftIO, Sync}
import cats.implicits._
import cats.{Applicative, Functor, Monad, Parallel, Traverse, ~>}
import io.epifab.tydal.runtime.Transaction.MapTransaction
import io.epifab.tydal.utils.EitherSupport
import shapeless.HList

import scala.collection.Factory

trait Transaction[+Output] {
  final def transact[F[+_]: Sync : Parallel : ContextShift : LiftIO](pool: ConnectionPool[F]): F[Either[DataError, Output]] = {
    pool.connection.use(connection =>
      (for {
        ac <- EitherT(pool.executor.safe(connection.getAutoCommit))
        _  <- if (ac) EitherT(pool.executor.safe(connection.setAutoCommit(false))) else EitherT.right(Sync[F].pure(()))
        sp <- EitherT(pool.executor.safe(connection.setSavepoint()))
        rs <- EitherT(run(connection, pool.executor).flatMap {
          case Right(result) => pool.executor.safe(connection.commit()).map(_.map(_ => result))
          case Left(error) =>   pool.executor.safe(connection.rollback(sp)).map(_ => Left(error))
        })
      } yield rs).value)
  }

  protected def run[F[+_]: Sync : Parallel : ContextShift : LiftIO](connection: Connection, jdbcExecutor: JdbcExecutor): F[Either[DataError, Output]]

  final def map[O2](f: Output => O2): Transaction[O2] =
    Transaction.MapTransaction(this, (x: Either[DataError, Output]) => x.map(f))

  final def recover[O2 >: Output](f: PartialFunction[DataError, O2]): Transaction[O2] =
    Transaction.MapTransaction(this, (x: Either[DataError, Output]) => x match {
      case Left(error) if f.isDefinedAt(error) => Right(f(error))
      case anything => anything
    })

  final def foreach(f: PartialFunction[Either[DataError, Output], IO[Unit]]): Transaction[Output] =
    Transaction.FlatMapTransaction(this, (x: Either[DataError, Output]) => x match {
      case x if f.isDefinedAt(x) => MapTransaction(LiftIO[Transaction].liftIO(f(x)), (_: Either[DataError, Unit]) => x)
      case Right(x) => Transaction.successful(x)
      case Left(x) => Transaction.failed(x)
    })

  final def flatMap[O2](f: Output => Transaction[O2]): Transaction[O2] =
    Transaction.FlatMapTransaction(this, (x: Either[DataError, Output]) => x match {
      case Right(o) => f(o)
      case Left(error) => Transaction.failed(error)
    })

  final def discardResults: Transaction[Unit] =
    map(_ => ())
}

object Transaction {
  implicit val monad: Monad[Transaction] = new Monad[Transaction] {
    override def pure[A](x: A): Transaction[A] =
      successful(x)

    override def flatMap[A, B](fa: Transaction[A])(f: A => Transaction[B]): Transaction[B] =
      fa.flatMap(f)

    override def tailRecM[A, B](a: A)(f: A => Transaction[Either[A, B]]): Transaction[B] =
      f(a).flatMap {
        case Left(a) => tailRecM(a)(f)
        case Right(b) => successful(b)
      }
  }

  implicit val liftIO: LiftIO[Transaction] = new LiftIO[Transaction] {
    override def liftIO[A](ioa: IO[A]): Transaction[A] = IOTransaction(ioa.map(Right(_)))
  }

  def sequential[C[_] : Traverse, Output](transactions: C[Transaction[Output]]): Transaction[C[Output]] =
    Traverse[C].sequence(transactions)

  def parallel[C[_] : Traverse, Output](transactions: C[Transaction[Output]])(implicit factory: Factory[Output, C[Output]]): Transaction[C[Output]] =
    ParTransactions(transactions)

  val unit: Transaction[Unit] = IOTransaction(IO.pure(Right(())))

  def failed(error: DataError): Transaction[Nothing] = IOTransaction(IO.pure(Left(error)))

  def successful[Output](value: Output): Transaction[Output] = IOTransaction(IO.pure(Right(value)))

  case class SimpleTransaction[Fields <: HList, Output](runnableStatement: RunnableStatement[Fields], statementExecutor: StatementExecutor[Connection, Fields, Output]) extends Transaction[Output] {
    override protected def run[F[+_]: Sync : Parallel : ContextShift : LiftIO](connection: Connection, jdbcExecutor: JdbcExecutor): F[Either[DataError, Output]] =
      statementExecutor.run(connection, jdbcExecutor, runnableStatement)
  }

  case class IOTransaction[Output](result: IO[Either[DataError, Output]]) extends Transaction[Output] {
    override protected def run[F[+_]: Sync : Parallel : ContextShift : LiftIO](connection: Connection, jdbcExecutor: JdbcExecutor): F[Either[DataError, Output]] =
      LiftIO[F].liftIO(result)
  }

  case class MapTransaction[O1, Output](transactionIO: Transaction[O1], f: Either[DataError, O1] => Either[DataError, Output]) extends Transaction[Output] {
    override def run[F[+_]: Sync : Parallel : ContextShift : LiftIO](connection: Connection, jdbcExecutor: JdbcExecutor): F[Either[DataError, Output]] =
      transactionIO.run(connection, jdbcExecutor).map(f)
  }

  case class FlatMapTransaction[O1, Output](transaction: Transaction[O1], f: Either[DataError, O1] => Transaction[Output]) extends Transaction[Output] {
    override def run[F[+_]: Sync : Parallel : ContextShift : LiftIO](connection: Connection, jdbcExecutor: JdbcExecutor): F[Either[DataError, Output]] =
      transaction.run(connection, jdbcExecutor).flatMap(f(_).run(connection, jdbcExecutor))
  }

  case class ParTransactions[C[_]: Traverse : Functor, Output](transactions: C[Transaction[Output]])(implicit factory: Factory[Output, C[Output]]) extends Transaction[C[Output]] {
    override def run[F[+_]: Sync : Parallel : ContextShift : LiftIO](connection: Connection, jdbcExecutor: JdbcExecutor): F[Either[DataError, C[Output]]] = {
      transactions
        .map(_.run[F](connection, jdbcExecutor))
        .parSequence
        .map((list: C[Either[DataError, Output]]) => EitherSupport.leftOrRights[C, DataError, Output](list))
    }
  }

  def apply[Fields <: HList, Output](
    runnableStatement: RunnableStatement[Fields])(
    implicit
    statementExecutor: StatementExecutor[Connection, Fields, Output]
  ): Transaction[Output] = SimpleTransaction(runnableStatement, statementExecutor)
}
