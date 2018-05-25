import domain._

import scala.language.higherKinds

class Col[T](field: Field[T], result: T)

class Row(cols: Map[String, Any]) {
  def get[T](field: Field[T]): Either[ExtractorError, T] =
    cols.get(field.alias).map(field.extractor.extract) match {
      case Some(t) => t
      case None => Left(ExtractorError("Field not found"))
    }
}

trait QueryRunner[F[_]] {
  def queryBuilder: QueryBuilder[SelectQuery]

  def select(query: SelectQuery): F[Seq[Row]]
}
