package io.epifab.yadl.domain

sealed trait Filter {
  def and(filter: Filter): Filter = Filter.And(this, filter)
  def or(filter: Filter): Filter = Filter.Or(this, filter)
}

object Filter {
  final case object Empty extends Filter {
    override def and(filter: Filter): Filter = filter
    override def or(filter: Filter): Filter = filter
  }
  final case class Or(filter1: Filter, filter2: Filter) extends Filter
  final case class And(filter1: Filter, filter2: Filter) extends Filter

  sealed trait Expression extends Filter
  final case class BinaryExpression(left: Expression.Clause[_], right: Expression.Clause[_], op: Expression.Op.BinaryOp) extends Expression
  final case class UniaryExpression(left: Expression.Clause[_], op: Expression.Op.UnaryOp) extends Expression

  object Expression {
    sealed trait Clause[T]
    object Clause {
      case class Field[T](field: io.epifab.yadl.domain.Field[T]) extends Clause[T]
      case class Literal[T, S](value: T)(implicit val adapter: FieldAdapter.Aux[T, S]) extends Clause[T] with Value[T] {
        type U = S
      }
    }

    sealed trait Op
    object Op {
      sealed trait BinaryOp extends Op
      final case object Equal extends BinaryOp
      final case object NotEqual extends BinaryOp
      final case object GT extends BinaryOp
      final case object LT extends BinaryOp
      final case object GTE extends BinaryOp
      final case object LTE extends BinaryOp
      final case object Like extends BinaryOp
      final case object In extends BinaryOp

      sealed trait UnaryOp extends Op
      final case object IsDefined extends UnaryOp
      final case object IsNotDefined extends UnaryOp
    }
  }
}
