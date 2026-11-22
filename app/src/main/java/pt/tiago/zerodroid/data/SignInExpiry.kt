package pt.tiago.zerodroid.data

/** The reason [pt.tiago.zerodroid.rpc.ConnState.AuthFailed] carries when an OIDC sign-in ran out. */
const val EXPIRED_MESSAGE = "Your sign-in expired"

private const val WARN_BEFORE_S = 300L
private const val SHORT_LIFETIME_S = 600L
private const val EXPIRY_MARGIN_S = 30L

sealed interface SignInStatus {
    data object NotOidc : SignInStatus
    data object Fresh : SignInStatus
    data class Expiring(val expiresAt: Long) : SignInStatus
    data object Expired : SignInStatus
}

private fun warnAt(obtainedAt: Long, expiresAt: Long): Long {
    val lifetime = expiresAt - obtainedAt
    return if (lifetime < SHORT_LIFETIME_S) obtainedAt + lifetime / 2 else expiresAt - WARN_BEFORE_S
}

/** Spec §5.6: warn 5 min before (half-life for tokens under 10 min), expired 30 s before the deadline. */
fun signInStatus(profile: DaemonProfile, now: Long): SignInStatus {
    if (!profile.isOidc) return SignInStatus.NotOidc
    val expiresAt = profile.tokenExpiresAt ?: return SignInStatus.Fresh
    val obtainedAt = profile.tokenObtainedAt ?: (expiresAt - SHORT_LIFETIME_S)
    return when {
        now >= expiresAt - EXPIRY_MARGIN_S -> SignInStatus.Expired
        now >= warnAt(obtainedAt, expiresAt) -> SignInStatus.Expiring(expiresAt)
        else -> SignInStatus.Fresh
    }
}

/** Epoch second at which [signInStatus] next changes, or null when it no longer will. */
fun nextSignInCheck(profile: DaemonProfile, now: Long): Long? {
    if (!profile.isOidc) return null
    val expiresAt = profile.tokenExpiresAt ?: return null
    val obtainedAt = profile.tokenObtainedAt ?: (expiresAt - SHORT_LIFETIME_S)
    return listOf(warnAt(obtainedAt, expiresAt), expiresAt - EXPIRY_MARGIN_S).firstOrNull { it > now }
}

/**
 * The OIDC sign-in as the connection sees it: the token clock, plus a refusal from the daemon
 * (clock skew, IdP revocation) that holds until the user signs in again.
 */
class SignInGate(private val now: () -> Long) {
    @Volatile private var refused = false

    fun status(profile: DaemonProfile): SignInStatus =
        if (profile.isOidc && refused) SignInStatus.Expired else signInStatus(profile, now())

    fun canConnect(profile: DaemonProfile): Boolean = status(profile) != SignInStatus.Expired

    /** True when this refusal must expire the sign-in; a late callback from a replaced client is ignored. */
    fun onAuthRequired(profile: DaemonProfile, fromCurrentClient: Boolean): Boolean {
        if (!profile.isOidc || !fromCurrentClient) return false
        refused = true
        return true
    }

    fun onRenewed() { refused = false }
}
