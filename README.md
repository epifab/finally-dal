# TYDAL

TYDAL (pronounced *tidal* `/ˈtʌɪd(ə)l/` and formerly YADL)
is a *type safe* PostgreSQL DSL for Scala.


## Getting started

### Installation

Step 1. Add the JitPack repository to your build file

```
resolvers += "jitpack" at "https://jitpack.io"
```

Step 2. Add the dependency

```
libraryDependencies += "com.github.epifab" % "tydal" % "1.x-SNAPSHOT"	
```


### What does it look like?

In English:

```
Find all students born between 1994 and 1998 who have taken at least one exam since 2010.
Also, find their best and most recent exam
```

In SQL:

```sql
SELECT
    s.id             AS sid,
    s.name           AS sname,
    e.score          AS escore,
    e.exam_timestamp AS etime,
    c.name           AS cname
FROM students AS s
INNER JOIN (
    SELECT 
        e1.student_id AS sid,
        MAX(s1.score) AS score
    FROM exams AS e1
    WHERE e1.exam_timestamp > '2010-01-01T00:00:00Z'::timestamp
    GROUP BY e1.student_id
) AS se1
ON se1.student_id = s.id
INNER JOIN (
    SELECT
        e2.student_id          AS sid,
        e2.score               AS score
        MAX(e2.exam_timestamp) AS etime
    FROM exams AS e2
    GROUP BY e2.student_id, e2.score
) AS se2
ON se2.student_id = se1.student_id AND se2.score = se1.score
INNER JOIN exams AS e
ON e.student_id = se2.student_id AND e.exam_timestamp = se2.etime
INNER JOIN courses AS c
ON c.id = e.course_id
WHERE s.date_of_birth > '1994-01-01'::date AND s.date_of_birth < '1998-12-31'::date
ORDER BY escore DESC, sname ASC
```

In Scala:

```scala
Select
  .from(Students as "s")
  .innerJoin(
    Select
      .from(Exams as "e1")
      .take(ctx => (
        ctx("e1").studentId as "sid",
        Max(ctx("e1").score) as "score"
      ))
      .where(_("e1").examTimestamp > "exam_min_date")
      .groupBy1(_("e1").studentId)
      .as("se1")
  )
  .on(_("sid") === _("s").id)
  .innerJoin(
    Select
      .from(Exams as "e2")
      .take(ctx => (
        ctx("e2").studentId as "sid",
        ctx("e2").score as "score",
        Max(ctx("e2").examTimestamp) as "etime"
      ))
      .groupBy(ctx => (ctx("e2").studentId, ctx("e2").score))
      .as("se2")
  )
  .on((se2, ctx) => se2("sid") === ctx("se1", "sid") and (se2("score") === ctx("se1", "score")))
  .innerJoin(Exams as "e")
  .on((e, ctx) => e.examTimestamp === ctx("se2", "etime") and (e.studentId === ctx("se2", "sid")))
  .innerJoin(Courses as "c")
  .on(_.id === _("e").courseId)
  .take(ctx => (
    ctx("s").id            as "sid",
    ctx("s").name          as "sname",
    ctx("e").score         as "escore",
    ctx("e").examTimestamp as "etime",
    ctx("c").name          as "cname"
  ))
  .where(ctx => ctx("s").dateOfBirth > "student_min_dob" and (ctx("s").dateOfBirth < "student_max_dob"))
  .sortBy(ctx => Descending(ctx("escore")) -> Ascending(ctx("sname")))
  .compile
  .withValues((
    "exam_min_date" ~~> Instant.parse("2010-01-01T00:00:00Z"),
    "student_min_dob" ~~> LocalDate.of(1994, 1, 1),
    "student_max_dob" ~~> LocalDate.of(1998, 12, 31)
  ))
  .mapTo[StudentExam]
  .as[Vector]
```

Please find more examples [here](src/test/scala/io/epifab/tydal/examples).


## More in-depth


### Query DSL

The idea behind this library is to provide a DSL as close as possible to the SQL language.

Different features are supported although the library does not cover the entire SQL universe nor has the ambition to do so.
You can explore some functionalities in the [examples package](src/main/scala/io/epifab/tydal/examples).


### Supported DBMS and data types

Currently, the only supported DBMS is **PostgreSQL** with the following data types:

Database type               | Scala type
---                         | ---
`char`, `varchar`, `text`   | `String`
`int`                       | `Int`
`float`                     | `Double`
`date`, `timestamp`         | `LocalDate`, `Instant` (java.time)
`enum`                      | Any type `T`
`arrays`                    | `Seq[T]` where `T` is any of the above
`json`                      | Any type `T`

In addition, every data type can be made optional and encoded as `Option`.

The mapping between a SQL data type and its Scala representation is defined via two *type classes* named `FieldEncoder` and `FieldDecoder`.
You can, of course, define new encoders/decoders in order to support custom types.
