package com.hypervolt.conduit.api

import cats.effect.Sync
import com.hypervolt.conduit.config.DbConfig
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult

object FlywayInit {

  def run[F[_]: Sync](jdbcUrl: String, user: String, password: String): F[MigrateResult] =
    Sync[F].blocking(
      Flyway
        .configure()
        .dataSource(jdbcUrl, user, password)
        .locations("classpath:db/migration")
        .load()
        .migrate()
    )

  def run[F[_]: Sync](db: DbConfig): F[MigrateResult] =
    run(db.jdbcUrl, db.user, db.password)
}
