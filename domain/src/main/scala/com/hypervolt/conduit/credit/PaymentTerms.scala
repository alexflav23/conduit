package com.hypervolt.conduit.credit

// The contractual payment-terms rule (doc 02 §C) that drives the invoice due date set at dispatch, and so the
// cash waterfall. Terms resolve billing_profile.payment_terms_days → credit_profile.terms_days → a 30-day
// default. Lifted out of CreditTermsService so the precedence + validation unit-test with no Postgres.
object PaymentTerms {

  val DefaultDays = 30

  def resolveTermsDays(billing: Option[Int], credit: Option[Int]): Int =
    billing.orElse(credit).getOrElse(DefaultDays)

  def validateTermsDays(days: Int): Either[String, Unit] =
    if (days < 0) Left("payment_terms_days must be >= 0") else Right(())
}
