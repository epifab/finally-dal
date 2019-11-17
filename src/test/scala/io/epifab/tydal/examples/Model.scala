package io.epifab.tydal.examples

import java.time.{Instant, LocalDate}

object Model {
  abstract sealed class Interest(val value: String)

  object Interest {
    def apply(value: String): Either[String, Interest] = value match {
      case Music.value => Right(Music)
      case Art.value => Right(Art)
      case History.value => Right(History)
      case Math.value => Right(Math)
      case _ => Left("Unknown interest")
    }

    case object Music extends Interest("music")
    case object Art extends Interest("art")
    case object History extends Interest("history")
    case object Math extends Interest("math")
  }

  case class Address(postcode: String, line1: String, line2: Option[String])

  case class Student(
    id: Int,
    name: String,
    email: Option[String],
    date_of_birth: LocalDate,
    address: Option[Address],
    interests: Seq[Interest]
  )

  case class StudentExam(id: Int, name: String, score: Int, time: Instant, course: String)

  case class Exam(
    student_id: Int,
    course_id: Int,
    score: Int,
    exam_timestamp: Instant,
    registration_timestamp: Option[Instant]
  )

  case class Course(
    id: Int,
    name: String
  )
}
