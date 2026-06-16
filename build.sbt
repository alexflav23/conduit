import sbt.*
import sbt.Keys.*

ThisBuild / version := {
  val short = git.gitHeadCommit.value.map(_.take(8))
  s"${short.getOrElse(System.currentTimeMillis.toString)}"
}

lazy val scala213 = "2.13.16"

// doc 14 §1.1 — no Double/Float in financial modules. CI gate (`sbt noFloatCheck`).
lazy val noFloatCheck = taskKey[Unit]("Reject Double/Float in financial modules (money, ledger)")

// doc 19 §B.1/§C.6 — no committed credential. CI gate (`sbt secretScan`), runs in the lint stage.
lazy val secretScan = taskKey[Unit]("Reject committed credential patterns (AWS/JWT/private-key/Stripe/DB-URL)")

// doc 19 §C.6 — forward-only migrations. CI gate (`sbt migrationCheck`): reject data-destroying DDL.
lazy val migrationCheck = taskKey[Unit]("Reject data-destroying DDL in Flyway migrations (forward-only)")

ThisBuild / scalaVersion := scala213

// Versions pinned to the house stack (Athena). See CLAUDE.md §2.
lazy val Versions = new {
  val circe          = "0.14.3"
  val logback        = "1.4.5"
  val config         = "1.4.1"
  val doobie         = "1.0.0-RC5"
  val flyway         = "11.12.0"
  val slf4j          = "1.7.32"
  val tapir          = "1.10.4"
  val postgres       = "42.7.7"
  val http4s         = "0.23.26"
  val cats           = "2.10.0"
  val catsEffect     = "3.5.4"
  val log4cats       = "2.7.0"
  val auth0JwksRsa   = "0.22.1"
  val auth0JavaJwt   = "4.4.0"
  val otel           = "1.40.0"
  val otelPromExport = "1.40.0-alpha"
  val otelRuntime    = "2.6.0-alpha"
  val otelSemconv    = "1.26.0-alpha"
  val tigerBeetle    = "0.16.46"
  val jackson        = "2.20.0"
  val pulsar         = "4.0.4"
  val avro           = "1.12.0"
  val avro4s         = "4.1.2"
  val squants        = "1.6.0"
  val fop            = "2.9"
  val stripe         = "29.5.0-beta.1"
  val awssdk2        = "2.34.8"

  val testContainers    = "0.41.0"
  val consulContainer   = "1.18.3"
  val postgresContainer = "1.20.0"
  val pulsarContainer   = "1.20.4"
  val weaver            = "0.8.4"
  val literally         = "1.2.0"
}

lazy val sharedSettings: Seq[Def.Setting[_]] = Seq(
  organization := "com.hypervolt",
  scalaVersion := scala213,
  Test / fork  := true,
  ThisBuild / scalacOptions ++= ProjectDefaults.scalacOptionsList,
  testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
  libraryDependencies ++= Seq(
    "org.slf4j"            % "log4j-over-slf4j"   % Versions.slf4j,
    "com.disneystreaming" %% "weaver-cats"        % Versions.weaver    % Test,
    "com.disneystreaming" %% "weaver-scalacheck"  % Versions.weaver    % Test,
    "org.typelevel"       %% "literally"          % Versions.literally % Test
  ),
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
  Test / testOptions ++= Seq(Tests.Argument("-oF"), Tests.Argument("-oD")),
  Test / javaOptions ++= Seq(
    "-Xms2G",
    "-Xmx2G",
    "-Djava.net.preferIPv4Stack=true",
    "-XX:MetaspaceSize=512m",
    "-XX:MaxMetaspaceSize=1g"
  )
)

lazy val defaultSettings = Defaults.coreDefaultSettings ++ sharedSettings

lazy val conduit = (project in file("."))
  .settings(
    name       := "conduit",
    moduleName := "conduit",
    defaultSettings,
    noFloatCheck := {
      val log   = streams.value.log
      val base  = (ThisBuild / baseDirectory).value / "domain" / "src" / "main" / "scala" / "com" / "hypervolt" / "conduit"
      val roots = Seq("money", "ledger", "pricing", "commission", "order", "batch", "inventory", "warranty", "purchasing", "stockops", "supply", "returns", "tax").map(base / _)
      val banned = """\b(Double|Float)\b""".r
      val offenders = roots.flatMap(r => (r ** "*.scala").get).flatMap { f =>
        IO.readLines(f).zipWithIndex.flatMap { case (line, idx) =>
          val code = line.split("//", 2).headOption.getOrElse("")
          if (banned.findFirstIn(code).isDefined) Seq(s"${f.getName}:${idx + 1}: ${line.trim}") else Seq.empty
        }
      }
      if (offenders.nonEmpty) sys.error(s"no-float rule violated in financial modules:\n${offenders.mkString("\n")}")
      else log.info(s"no-float check passed (${roots.size} financial module roots clean)")
    },
    secretScan := {
      val log  = streams.value.log
      val root = (ThisBuild / baseDirectory).value
      // scan code + config (not prose docs); never the build artefacts or VCS internals
      val exts    = Set("scala", "sql", "conf", "yml", "yaml", "sh", "nix", "properties", "env", "json")
      val skipDir = Set("target", ".git", ".bsp", ".metals", ".bloop", ".idea")
      // patterns are crafted not to self-match this task's own source (no literal credential here triggers them)
      val patterns: Seq[(String, scala.util.matching.Regex)] = Seq(
        "AWS access key id"    -> "AKIA[0-9A-Z]{16}".r,
        "private key block"    -> "BEGIN (?:RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY".r,
        "JWT"                  -> raw"eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}".r,
        "Stripe live key"      -> "(?:sk|rk)_live_[0-9A-Za-z]{16,}".r,
        "DB URL with password" -> raw"[a-z][a-z0-9+.\-]*://[^:@/\s]+:[^@/\s]+@[^/\s]+".r
      )
      def skipped(f: File): Boolean = {
        val rel = f.relativeTo(root).map(_.getPath).getOrElse(f.getName)
        skipDir.exists(d => rel == d || rel.startsWith(d + "/") || rel.contains("/" + d + "/"))
      }
      val files = (root ** "*").get
        .filter(f => f.isFile && exts.contains(f.getName.split('.').lastOption.getOrElse("")) && !skipped(f))
      val offenders = files.flatMap(f =>
        IO.readLines(f).zipWithIndex.flatMap { case (line, idx) =>
          patterns.collect { case (label, re) if re.findFirstIn(line).isDefined => s"${f.getName}:${idx + 1}: $label" }
        }
      )
      if (offenders.nonEmpty) sys.error(s"secretScan: committed credential(s) detected:\n${offenders.mkString("\n")}")
      else log.info(s"secretScan passed (${files.size} files, ${patterns.size} patterns, no credentials)")
    },
    migrationCheck := {
      val log = streams.value.log
      val dir = (ThisBuild / baseDirectory).value / "api" / "src" / "main" / "resources" / "db" / "migration"
      // data-destroying DDL is forbidden (forward-only); safe object drops (INDEX/CONSTRAINT/TRIGGER/VIEW/TYPE) are fine
      val banned: Seq[(String, scala.util.matching.Regex)] = Seq(
        "DROP TABLE"    -> "(?i)\\bDROP\\s+TABLE\\b".r,
        "DROP COLUMN"   -> "(?i)\\bDROP\\s+COLUMN\\b".r,
        "TRUNCATE"      -> "(?i)\\bTRUNCATE\\b".r,
        "DROP SCHEMA"   -> "(?i)\\bDROP\\s+SCHEMA\\b".r,
        "DROP DATABASE" -> "(?i)\\bDROP\\s+DATABASE\\b".r,
        "DELETE FROM"   -> "(?i)\\bDELETE\\s+FROM\\b".r
      )
      val files = (dir ** "*.sql").get
      val offenders = files.flatMap(f =>
        IO.readLines(f).zipWithIndex.flatMap { case (line, idx) =>
          val code = line.split("--", 2).headOption.getOrElse("")
          banned.collect { case (label, re) if re.findFirstIn(code).isDefined => s"${f.getName}:${idx + 1}: $label" }
        }
      )
      if (offenders.nonEmpty)
        sys.error(s"migrationCheck: forward-only rule violated (data-destroying DDL):\n${offenders.mkString("\n")}")
      else log.info(s"migrationCheck passed (${files.size} migrations, forward-only)")
    }
  )
  .aggregate(domain, api, apiIt, consumer, scripting)

// Domain logic, services, repositories, the financial-integrity core (Money/RoundingPolicy/allocate),
// the event envelope + Avro schemas, and the TigerBeetle posting model.
lazy val domain = (project in file("domain"))
  .settings(
    sharedSettings,
    name       := "conduit-domain",
    moduleName := "conduit-domain",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-core"                  % Versions.tapir,
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server"         % Versions.tapir,
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe"            % Versions.tapir,
      "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle"     % Versions.tapir,
      "com.softwaremill.sttp.tapir" %% "tapir-opentelemetry-metrics" % Versions.tapir,
      "com.softwaremill.sttp.tapir" %% "tapir-cats"                  % Versions.tapir,
      "com.auth0"                    % "jwks-rsa"                     % Versions.auth0JwksRsa exclude ("com.fasterxml.jackson.core", "jackson-databind"),
      "com.auth0"                    % "java-jwt"                     % Versions.auth0JavaJwt exclude ("com.fasterxml.jackson.core", "jackson-databind"),
      "io.circe"                    %% "circe-generic"                % Versions.circe,
      "io.circe"                    %% "circe-parser"                 % Versions.circe,
      "org.typelevel"               %% "cats-core"                    % Versions.cats,
      "org.typelevel"               %% "cats-effect"                  % Versions.catsEffect,
      "org.typelevel"               %% "log4cats-core"                % Versions.log4cats,
      "org.typelevel"               %% "log4cats-slf4j"               % Versions.log4cats,
      "ch.qos.logback"               % "logback-classic"              % Versions.logback,
      "com.typesafe"                 % "config"                       % Versions.config,
      "org.http4s"                  %% "http4s-ember-server"          % Versions.http4s,
      "org.http4s"                  %% "http4s-ember-client"          % Versions.http4s,
      "org.http4s"                  %% "http4s-circe"                 % Versions.http4s,
      "org.http4s"                  %% "http4s-dsl"                   % Versions.http4s,
      "org.postgresql"               % "postgresql"                   % Versions.postgres,
      "org.tpolecat"                %% "doobie-core"                  % Versions.doobie,
      "org.tpolecat"                %% "doobie-postgres"              % Versions.doobie,
      "org.tpolecat"                %% "doobie-postgres-circe"        % Versions.doobie,
      "org.tpolecat"                %% "doobie-hikari"                % Versions.doobie,
      "io.opentelemetry"             % "opentelemetry-sdk"            % Versions.otel,
      "io.opentelemetry"             % "opentelemetry-exporter-prometheus" % Versions.otelPromExport,
      "io.opentelemetry.instrumentation" % "opentelemetry-runtime-telemetry-java17" % Versions.otelRuntime,
      "io.opentelemetry.semconv"     % "opentelemetry-semconv"        % Versions.otelSemconv,
      "com.tigerbeetle"              % "tigerbeetle-java"             % Versions.tigerBeetle,
      "com.fasterxml.jackson.core"   % "jackson-core"                 % Versions.jackson,
      "com.fasterxml.jackson.core"   % "jackson-databind"             % Versions.jackson,
      "org.apache.pulsar"            % "pulsar-client"                % Versions.pulsar,
      "org.apache.pulsar"            % "pulsar-client-admin"          % Versions.pulsar,
      "com.sksamuel.avro4s"         %% "avro4s-core"                  % Versions.avro4s,
      "org.apache.avro"              % "avro"                         % Versions.avro,
      "org.xerial.snappy"            % "snappy-java"                  % "1.1.10.7", // avro4s binary encode path
      // doc 17 §4.4 — legal-document PDF engine. Apache FOP (XSL-FO → PDF/A), NOT PDFBox: template-driven
      // layout, embeddable CJK+Thai fonts, deterministic output (fixed creation date) for sha re-performability.
      "org.apache.xmlgraphics"       % "fop"                          % Versions.fop,
      // doc 13 §payments — Stripe (ported from Athena) is one source feeding the ledger settlement; webhook
      // signature verification uses Stripe's own SDK. Swappable: the ledger settlement is the system of record.
      "com.stripe"                   % "stripe-java"                  % Versions.stripe,
      // doc 17 §6 — finalised documents are WORM in S3 (object-lock + versioning). LocalStack stands in for S3
      // in dev/CI (the bucket is provisioned by terraform; see terraform/conduit-records).
      "software.amazon.awssdk"       % "s3"                           % Versions.awssdk2,

      "org.typelevel"               %% "squants"                      % Versions.squants,
      // Test-only: ToolBox-based "does not type-check" assertions (cross-currency safety).
      "org.scala-lang"               % "scala-compiler"               % scala213 % Test
    )
  )

lazy val api = (project in file("api"))
  .enablePlugins(JavaServerAppPackaging)
  .settings(
    defaultSettings,
    sharedSettings,
    name       := "conduit-api",
    moduleName := "conduit-api",
    libraryDependencies ++= Seq(
      "org.flywaydb" % "flyway-core"                % Versions.flyway,
      "org.flywaydb" % "flyway-database-postgresql" % Versions.flyway
    ),
    run / fork := true
  )
  .dependsOn(domain % "compile->compile;test->test")

lazy val apiIt = (project in file("api-it"))
  .settings(
    sharedSettings,
    name       := "conduit-api-it",
    moduleName := "conduit-api-it",
    libraryDependencies ++= Seq(
      "com.dimafeng"       %% "testcontainers-scala-postgresql" % Versions.testContainers % Test,
      "org.testcontainers"  % "postgresql"                      % Versions.postgresContainer % Test,
      "org.testcontainers"  % "pulsar"                          % Versions.pulsarContainer   % Test,
      "org.testcontainers"  % "consul"                          % Versions.consulContainer   % Test,
      "org.testcontainers"  % "localstack"                      % Versions.postgresContainer % Test
    ),
    Test / fork := true
  )
  .dependsOn(
    api      % "compile->compile;test->test",
    domain   % "compile->compile;test->test",
    consumer % "compile->compile" // integration tests exercise the real consumer extractors end-to-end
  )

lazy val consumer = (project in file("consumer"))
  .enablePlugins(JavaAppPackaging)
  .settings(
    sharedSettings,
    name       := "conduit-consumer",
    moduleName := "conduit-consumer"
  )
  .dependsOn(domain)

lazy val scripting = (project in file("scripting"))
  .settings(
    sharedSettings,
    name                 := "conduit-scripting",
    moduleName           := "conduit-scripting",
    Compile / run / fork := true
  )
  .dependsOn(domain)

// Avro BACKWARD-compat gate (doc 03 §2). Seed: the EventEnvelope schema must derive + round-trip;
// expands to a registry diff once schemas are registered.
addCommandAlias("schemaCheck", "domain/testOnly com.hypervolt.conduit.event.EventEnvelopeSpec")
addCommandAlias("fmt", ";scalafmt;Test/scalafmt")
addCommandAlias(
  "fullRebuild",
  List("reload", "clean", "Test / clean", "compile", "Test / compile").mkString(";")
)

// M-Assurance C (doc 29): coverage measurement. `coverMoneyCore` is the fast, no-Docker baseline over the
// domain unit tests (Money/allocate/RoundingPolicy/Fingerprint/PolicyEngine/Projection — the pure money
// logic). The full money-path gate (ledger/revenue/intercompany/batch, exercised by the testcontainers
// suites) runs in CI: `sbt clean coverage apiIt/test domain/coverageReport`, then inspect scoverage-report.
addCommandAlias(
  "coverMoneyCore",
  List("clean", "coverage", "domain/test", "domain/coverageReport").mkString(";")
)
