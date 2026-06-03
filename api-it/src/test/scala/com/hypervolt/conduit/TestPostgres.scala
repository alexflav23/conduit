package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.hypervolt.conduit.api.FlywayInit
import com.hypervolt.conduit.db.Transactor
import doobie.hikari.HikariTransactor
import org.testcontainers.utility.DockerImageName

// Spins a real Postgres 16, runs Flyway, hands back a Transactor. Shared per integration suite.
object TestPostgres {

  def transactor: Resource[IO, HikariTransactor[IO]] =
    Resource
      .make(IO {
        val c = PostgreSQLContainer(dockerImageNameOverride = DockerImageName.parse("postgres:16"))
        c.start()
        c
      })(c => IO(c.stop()))
      .flatMap { c =>
        Resource
          .eval(FlywayInit.run[IO](c.jdbcUrl, c.username, c.password))
          .flatMap(_ => Transactor.build[IO](c.jdbcUrl, c.username, c.password))
      }
}
