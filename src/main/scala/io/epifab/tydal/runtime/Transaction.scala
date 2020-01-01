package io.epifab.tydal.runtime

import java.sql.Connection

import cats.effect.{ContextShift, IO, LiftIO, Sync}
import cats.implicits._
import cats.{Functor, Monad, Parallel, Traverse}
import io.epifab.tydal.utils.EitherSupport
import shapeless.HList

trait Transaction[+Output] {
  final def transact[F[+_]: Sync : Parallel : ContextShift : LiftIO](pool: ConnectionPool[F]): F[Either[DataError, Output]] =
    pool.connection.use(connection =>
      ensureNonAutoCommit(connection).flatMap(_ =>
        Sync[F].delay(connection.setSavepoint()).flatMap(savePoint =>
          run(connection).flatMap {
            case Left(error) => Sync[F].delay(connection.rollback(savePoint)).map(_ => Left(error))
            case Right(results) => Sync[F].delay(connection.commit()).map(_ => Right(results))
          }
        )
      )
    )

  private def ensureNonAutoCommit[F[+_]: Sync : Monad](connection: Connection): F[Unit] = for {
    autoCommit <- Sync[F].delay(connection.getAutoCommit)
    _ <- if (autoCommit) Sync[F].delay(connection.setAutoCommit(false)) else Sync[F].pure(())
  } yield ()

  protected def run[F[+_]: Sync : Parallel : ContextShift : LiftIO](connection: Connection): F[Either[DataError, Output]]

  final def map[O2](f: Output => O2): Transaction[O2] =
    Transaction.MapTransaction(this, (x: Either[DataError, Output]) => x.map(f))

  final def recover[O2 >: Output](f: PartialFunction[DataError, O2]): Transaction[O2] =
    Transaction.MapTransaction(this, (x: Either[DataError, Output]) => x match {
      case Left(error) if f.isDefinedAt(error) => Right(f(error))
      case anything => anything
    })

  final def foreach(f: PartialFunction[Either[DataError, Output], Unit]): Transaction[Output] =
    Transaction.MapTransaction(this, (x: Either[DataError, Output]) => x match {
      case x if f.isDefinedAt(x) => f(x); x
      case x => x
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

  val unit: Transaction[Unit] = IOTransaction(IO.pure(Right(())))

  def vector[Output](transactions: Vector[Transaction[Output]]): Transaction[Vector[Output]] =
    Traverse[Vector].sequence(transactions)

  def list[Output](transactions: List[Transaction[Output]]): Transaction[List[Output]] =
    Traverse[List].sequence(transactions)

  def parVector[Output](transactions: Vector[Transaction[Output]])(implicit cs: ContextShift[IO]): Transaction[Seq[Output]] =
    ParTransactions(transactions)

  def parList[Output](transactions: List[Transaction[Output]])(implicit cs: ContextShift[IO]): Transaction[Seq[Output]] =
    ParTransactions(transactions)

  def failed(error: DataError): Transaction[Nothing] = IOTransaction(IO.pure(Left(error)))

  def successful[Output](value: Output): Transaction[Output] = IOTransaction(IO.pure(Right(value)))

  case class SimpleTransaction[Fields <: HList, Output](runnableStatement: RunnableStatement[Fields], statementExecutor: StatementExecutor[Connection, Fields, Output]) extends Transaction[Output] {
    override protected def run[F[+_]: Sync : Parallel : ContextShift : LiftIO](connection: Connection): F[Either[DataError, Output]] =
      statementExecutor.run(connection, runnableStatement)
  }

  case class IOTransaction[Output](result: IO[Either[DataError, Output]]) extends Transaction[Output] {
    override protected def run[F[+_]: Sync : Parallel : ContextShift : LiftIO](connection: Connection): F[Either[DataError, Output]] =
      LiftIO[F].liftIO(result)
  }

  case class MapTransaction[O1, Output](transactionIO: Transaction[O1], f: Either[DataError, O1] => Either[DataError, Output]) extends Transaction[Output] {
    override def run[F[+_]: Sync : Parallel : ContextShift : LiftIO](connection: Connection): F[Either[DataError, Output]] =
      transactionIO.run(connection).map(f)
  }

  case class FlatMapTransaction[O1, Output](transaction: Transaction[O1], f: Either[DataError, O1] => Transaction[Output]) extends Transaction[Output] {
    override def run[F[+_]: Sync : Parallel : ContextShift : LiftIO](connection: Connection): F[Either[DataError, Output]] =
      transaction.run(connection).flatMap(f(_).run(connection))
  }

  case class ParTransactions[C[_]: Traverse : Functor, Output](transactions: C[Transaction[Output]]) extends Transaction[Seq[Output]] {
    override def run[F[+_]: Sync : Parallel : ContextShift : LiftIO](connection: Connection): F[Either[DataError, Seq[Output]]] = {
      Functor[C].map(transactions)(_.run[F](connection)).parSequence
        .map(list => EitherSupport.leftOrRights[C, DataError, Output, Vector](list))
    }
  }

  def apply[Fields <: HList, Output](
    runnableStatement: RunnableStatement[Fields])(
    implicit
    statementExecutor: StatementExecutor[Connection, Fields, Output]
  ): Transaction[Output] = SimpleTransaction(runnableStatement, statementExecutor)
}
