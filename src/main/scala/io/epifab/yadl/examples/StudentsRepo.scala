package io.epifab.yadl.examples

import java.time.LocalDate

import io.epifab.yadl.domain._
import io.epifab.yadl.implicits._

import scala.language.higherKinds

trait StudentsRepo[F[_]] extends Repo[F] {
  import Adapters._

  val studentsDS = new Schema.StudentsTable

  def deleteStudent(id: Int): F[Either[DALError, Int]] =
    Delete(studentsDS)
      .where(studentsDS.id === Value(id))
      .execute()

  def createStudent(student: Student): F[Either[DALError, Int]] =
    Insert
      .into(studentsDS)
      .set(
        studentsDS.id -> student.id,
        studentsDS.name -> student.name,
        studentsDS.email -> student.email,
        studentsDS.dateOfBirth -> student.dateOfBirth,
        studentsDS.address -> student.address,
        studentsDS.interests -> student.interests
      )
      .execute()

  def updateStudent(student: Student): F[Either[DALError, Int]] =
    Update(studentsDS)
      .set(
        studentsDS.name -> student.name,
        studentsDS.email -> student.email,
        studentsDS.dateOfBirth -> student.dateOfBirth,
        studentsDS.address -> student.address,
        studentsDS.interests -> student.interests
      )
      .where(studentsDS.id === Value(student.id))
      .execute()

  def findStudent(id: Int): F[Either[DALError, Option[Student]]] =
    TypedSelect
      .from(studentsDS)
      .take(studentsDS.*)
      .where(studentsDS.id === Value(id))
      .sortBy(studentsDS.id.asc)
      .inRange(0, 1)
      .fetchOne

  def findStudentsByInterests(interests: Seq[Interest]): F[Either[DALError, Seq[Student]]] =
    TypedSelect
      .from(studentsDS)
      .take(studentsDS.*)
      .where(studentsDS.interests contains Value(interests))
      .sortBy(studentsDS.id.asc)
      .fetchMany

  def findStudentsByAnyInterest(interests: Seq[Interest]): F[Either[DALError, Seq[Student]]] =
    TypedSelect
      .from(studentsDS)
      .take(studentsDS.*)
      .where(studentsDS.interests overlaps Value(interests))
      .sortBy(studentsDS.id.asc)
      .fetchMany

  def findStudentByName(name: String): F[Either[DALError, Seq[Student]]] =
    TypedSelect
      .from(studentsDS)
      .take(studentsDS.*)
      .where(studentsDS.name like Value(name))
      .sortBy(studentsDS.id.asc)
      .fetchMany

  def findStudentByEmail(email: String): F[Either[DALError, Seq[Student]]] =
    TypedSelect
      .from(studentsDS)
      .take(studentsDS.*)
      .where(studentsDS.email like Value(email))
      .sortBy(studentsDS.id.asc)
      .fetchMany

  def findStudentsWithoutEmail(): F[Either[DALError, Seq[Student]]] =
    TypedSelect
      .from(studentsDS)
      .take(studentsDS.*)
      .where(studentsDS.email.isNotDefined)
      .sortBy(studentsDS.id.asc)
      .fetchMany

  def findStudents(ids: Int*): F[Either[DALError, Seq[Student]]] =
    TypedSelect
      .from(studentsDS)
      .take(studentsDS.*)
      .where(studentsDS.id in Value(ids))
      .sortBy(studentsDS.id.asc)
      .fetchMany

  def findStudentExamStats(id: Int): F[Either[DALError, Option[StudentExams]]] = {
    val examsProjections = new Schema.ExamsProjection(new Schema.ExamsTable)

    TypedSelect
      .from(examsProjections)
      .take(examsProjections.*)
      .where(examsProjections.studentId === Value(id))
      .fetchOne
  }

  def findStudentsByDateOfBirth(dates: LocalDate*): F[Either[DALError, Seq[Student]]] = {
    TypedSelect
      .from(studentsDS)
      .take(studentsDS.*)
      .where(studentsDS.dateOfBirth in Value(dates))
      .fetchMany
  }
}
