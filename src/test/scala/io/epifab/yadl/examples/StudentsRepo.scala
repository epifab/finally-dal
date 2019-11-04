package io.epifab.yadl.examples

import java.time.LocalDate

import io.epifab.yadl.Implicits.ExtendedTag
import io.epifab.yadl._
import io.epifab.yadl.examples.Model.{Student, StudentExam}
import io.epifab.yadl.examples.Schema._
import io.epifab.yadl.fields._
import io.epifab.yadl.runner.{SelectStatement, TransactionIO}
import shapeless.HNil

object StudentsRepo {
  private val studentExamsQuery =
    Select
      .from(Students as "s")
      .join($ => (Exams as "e").on(_ ("student_id") === $("s", "id")))
      .join($ => (Courses as "c").on(_ ("id") === $("e", "course_id")))
      .take($ => (
        $("s", "id").as["sid"],
        $("s", "name").as["sname"],
        $("e", "score").as["rate"],
        $("e", "exam_timestamp").as["etime"],
        $("c", "name").as["cname"]
      ))
      .where(_ ("s", "id") === "sid")
      .compile

  def findById(id: Int): TransactionIO[Option[Student]] =
    Select
      .from(Students as "s")
      .take(_("s").*)
      .where(_("s", "id") === "student_id")
      .compile
      .withValues(Tuple1("student_id" ~~> id))
      .takeFirst
      .mapTo[Student]

  def findAllBy[C <: Column[_], T]
      (column: StudentsSchema => C, value: T)
      (implicit
       fieldEncoder: FieldEncoder[T],
       fieldDecoder: FieldDecoder[T],
       areComparable: AreComparable[C, NamedPlaceholder[T] with Tag["x"]]): TransactionIO[Seq[Student]] = {
    Select
      .from(Students as "s")
      .take(_("s").*)
      .where { $ => column($("s").schema) === NamedPlaceholder[T, "x"] }
      .compile
      .withValues(Tuple1("x" ~~> value))
      .mapTo[Student]
  }

  def findAllBy(id: Option[Int], email: Option[Option[String]]): TransactionIO[Seq[Student]] = {
    Select
      .from(Students as "s")
      .take(_("s").*)
      .where { $ => $("s", "id") === OptionalPlaceholderValue(id) }
      .compile
      .withValues(())
      .mapTo[Student]
  }

  def findStudentExams(id: Int): TransactionIO[Seq[StudentExam]] = {
    studentExamsQuery
      .withValues(Tuple1("sid" ~~> id))
      .mapTo[StudentExam]
  }

  def add(student: Student): TransactionIO[Int] = {
    Insert
      .into(Students)
      .compile
      .withValues {
        (
          "id" ~~> student.id,
          "name" ~~> student.name,
          "email" ~~> student.email,
          "date_of_birth" ~~> student.dateOfBirth,
          "address" ~~> student.address,
          "interests" ~~> student.interests
        )
      }
  }

  def updateNameAndEmail(id: Int, name: String, email: Option[String]): TransactionIO[Int] =
    Update(Students)
      .fields(s => (s.name, s.email))
      .where(_.id === "id")
      .compile
      .withValues {
        (
          "name" ~~> name,
          "email" ~~> email,
          "id" ~~> id
        )
      }

  def remove(id: Int): TransactionIO[Int] =
    Delete.from(Students)
      .where(_.id === "id")
      .compile
      .withValues {
        Tuple1("id" ~~> id)
      }

  lazy val removeAll: TransactionIO[Int] =
    Delete
      .from(Students)
      .compile
      .withValues(())
}
