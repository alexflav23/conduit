package com.hypervolt.conduit.credit

import weaver.SimpleIOSuite

// The contractual payment-terms rule (doc 02 §C): precedence + validation — Docker-free.
object PaymentTermsSpec extends SimpleIOSuite {

  pureTest("resolveTermsDays: billing wins, then credit, then the 30-day default") {
    expect(PaymentTerms.resolveTermsDays(Some(45), Some(60)) == 45) and
      expect(PaymentTerms.resolveTermsDays(None, Some(60)) == 60) and
      expect(PaymentTerms.resolveTermsDays(None, None) == 30)
  }

  pureTest("validateTermsDays rejects negative terms, accepts zero and positive") {
    expect(PaymentTerms.validateTermsDays(-1).isLeft) and
      expect(PaymentTerms.validateTermsDays(0).isRight) and
      expect(PaymentTerms.validateTermsDays(30).isRight)
  }
}
