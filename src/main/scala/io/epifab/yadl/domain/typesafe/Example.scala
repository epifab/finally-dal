package io.epifab.yadl.domain.typesafe

import io.epifab.yadl.domain.typesafe.Implicits._
import shapeless.{::, HNil}

object Example {
  class Students extends Table[
    "students",
      (Term[Int] AS "id") ::
      (Term[String] AS "name") ::
      HNil
  ] {
    def id = term["id"].get
    def name = term["name"].get
  }

  object Students {
    def as[T]: Students AS T = (new Students).as[T]
  }

  class Exams extends Table[
    "exams",
      (Term[Int] AS "student_id") ::
      (Term[Int] AS "course_id") ::
      (Term[Int] AS "score") ::
      HNil
  ] {
    def studentId = term["student_id"].get
    def courseId = term["course_id"].get
    def score = term["score"].get
  }

  object Exams {
    def as[T]: Exams AS T = (new Exams).as[T]
  }

  class Courses extends Table[
    "courses",
      (Term[Int] AS "id") ::
      (Term[String] AS "name") ::
      HNil
  ] {
    def id = term["id"].get
    def name = term["name"].get
  }

  object Courses {
    def as[T]: Courses AS T = (new Courses).as[T]
  }

  type MaxScoreSubQuery[E, C] = Select[HNil, AS[Exams, E] :: Aggregation[Int, Option[Int]] with Alias["max_score"] :: Aggregation[Int, Option[Int]] with Alias["course_id"] :: HNil, Term[Int] with Alias["student_id"] :: HNil, AS[Exams, E] :: HNil]

  def maxScoreSubQuery[E, C]: MaxScoreSubQuery[E, C] =
    Select
      .from(Exams.as[E])
      .groupBy(ctx => ctx.source[E].get.term["student_id"].get :: HNil)
      .take(ctx => ctx.source[E].get ::
        Max(ctx.source[E].get.term["score"].get).as["max_score"] ::
        Min(ctx.source[E].get.term["course_id"].get).as["course_id"] ::
        HNil)

  val studentsSelect =
    Select
      .from(Students.as["s"])
      .join(ctx => maxScoreSubQuery["ee", "cc"].as["ms"]
        .on(_.source["ee"].get.term["student_id"].get === ctx.source["s"].get.id))
      .join(ctx => Courses.as["c"].on(_.id ===
        ctx.source["ms"].get.term["course_id"].get))
      .take(ctx =>
        ctx.source["s"].get.terms ::
        ctx.source["ms"].get.terms ::
        ctx.source["c"].get.terms
      )
      .withPlaceholder[Int, "studentId"]
      .where(ctx => ctx.source["s"].get.id === ctx.placeholder["studentId"].get)
}
