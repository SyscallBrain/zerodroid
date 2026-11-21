package pt.tiago.zerodroid.data

/**
 * Matches zerocode's `renewal_due`: renew at >= 50% of the certificate lifetime.
 * All values are Unix seconds (the daemon's `not_after` unit).
 */
fun renewalDue(issuedAt: Long, notAfter: Long, now: Long): Boolean {
    if (notAfter <= 0) return false
    val lifetime = notAfter - issuedAt
    if (lifetime <= 0) return true
    return now - issuedAt >= lifetime / 2
}
