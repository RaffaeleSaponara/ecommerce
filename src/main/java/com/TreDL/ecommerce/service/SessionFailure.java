package com.TreDL.ecommerce.service;

/** Deliberately excludes provider exception details and payloads. */
class SessionFailure extends RuntimeException {
    final boolean retryableConflict;
    SessionFailure(boolean conflict){super("Sessione Stripe non disponibile: riprovare o richiedere riconciliazione");retryableConflict=conflict;}
}
