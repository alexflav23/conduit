package com.hypervolt.conduit.db

import cats.effect.Async
import cats.effect.Resource
import com.hypervolt.conduit.config.DbConfig
import com.zaxxer.hikari.HikariConfig
import doobie.hikari.HikariTransactor

object Transactor {

  def build[F[_]: Async](jdbcUrl: String, user: String, password: String): Resource[F, HikariTransactor[F]] = {
    val hc = new HikariConfig()
    hc.setDriverClassName("org.postgresql.Driver")
    hc.setJdbcUrl(jdbcUrl)
    hc.setUsername(user)
    hc.setPassword(password)
    hc.setPoolName("Conduit Connection Pool")
    hc.setConnectionInitSql("SET application_name = 'conduit'")
    HikariTransactor.fromHikariConfig[F](hc)
  }

  def build[F[_]: Async](db: DbConfig): Resource[F, HikariTransactor[F]] =
    build(db.jdbcUrl, db.user, db.password)
}
