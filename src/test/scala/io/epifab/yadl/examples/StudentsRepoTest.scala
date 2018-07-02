package io.epifab.yadl.examples

import java.sql.{Connection, DriverManager}

import cats.Applicative
import cats.data.EitherT
import io.epifab.yadl.domain.{DALError, Delete, QueryRunner}
import io.epifab.yadl.utils.EitherSupport._
import org.scalatest.Matchers._
import org.scalatest.{BeforeAndAfterAll, FlatSpec}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class StudentsRepoTest extends FlatSpec with BeforeAndAfterAll {
  import cats.implicits._
  import io.epifab.yadl.postgres._

  import scala.concurrent.ExecutionContext.Implicits.global

  implicit class ExtendedFuture[T](f: Future[T]) {
    def eventually: T = Await.result[T](f, 5.seconds)
  }

  val student1 = Student(1, "John Doe", Some("john@doe.com"))
  val student2 = Student(2, "Jane Doe", Some("jane@doe.com"))
  val student3 = Student(3, "Jack Roe", None)
  val course1 = Course(1, "Math")
  val course2 = Course(2, "Astronomy")
  val exam1 = Exam(studentId = 1, courseId = 1, 24)
  val exam2 = Exam(studentId = 2, courseId = 1, 29)
  val exam3 = Exam(studentId = 2, courseId = 2, 30)

  val connection: Connection = DriverManager
    .getConnection(
      s"jdbc:postgresql://${sys.env("DB_HOST")}/${sys.env("DB_NAME")}?user=${sys.env("DB_USER")}&password=${sys.env("DB_PASS")}")

  object repos extends StudentsRepo[Future] with ExamsRepo[Future] with CourseRepo[Future] {
    override implicit val queryRunner: QueryRunner[Future] = asyncQueryRunner(connection)
    override implicit val A: Applicative[Future] = implicitly
  }

  def tearDown(): Unit = {
    repos.queryRunner.run(Delete(new Schema.ExamsTable("e")))
      .flatMap(_ => repos.queryRunner.run(Delete(new Schema.CoursesTable("c"))))
      .flatMap(_ => repos.queryRunner.run(Delete(new Schema.StudentsTable("s"))))
      .eventually shouldBe 'Right
  }

  override def beforeAll(): Unit = {
    tearDown()

    Future.sequence(Seq(
      repos.createStudent(student1),
      repos.createStudent(student2),
      repos.createStudent(student3)
    )).flatMap(_ => Future.sequence(Seq(
      repos.createCourse(course1),
      repos.createCourse(course2)
    ))).flatMap(_ => Future.sequence(Seq(
      repos.createExam(exam1),
      repos.createExam(exam2),
      repos.createExam(exam3)
    )))
    .map(firstLeftOrRights)
    .eventually shouldBe 'Right
  }

  override def afterAll(): Unit = {
    tearDown()
  }

  "The query runner" should "retrieve a student by ID" in {
    repos.findStudent(2).eventually shouldBe Right(Some(student2))
  }

  it should "retrieve a list of students by name" in {
    repos.findStudentByName("%Doe").eventually shouldBe Right(Seq(student1, student2))
  }

  it should "retrieve a list of students by ids" in {
    repos.findStudents(2, 3, 4).eventually shouldBe Right(Seq(student2, student3))
  }

  it should "retrieve a list of students by email" in {
    repos.findStudentByEmail("%@doe.com").eventually shouldBe Right(Seq(student1, student2))
  }

  it should "retrieve students with missing email" in {
    repos.findStudentsWithoutEmail().eventually shouldBe Right(Seq(student3))
  }

  it should "update a student" in {
    val edited: EitherT[Future, DALError, Option[Student]] = for {
      _ <- EitherT(repos.updateStudent(student3.copy(name = "Edited")))
      edited <- EitherT(repos.findStudent(3))
    } yield edited

    edited.value.eventually.map(_.map(_.name)) shouldBe Right(Some("Edited"))
  }
}
