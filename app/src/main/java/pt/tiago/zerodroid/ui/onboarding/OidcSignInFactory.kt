package pt.tiago.zerodroid.ui.onboarding

import android.content.Context
import pt.tiago.zerodroid.CrashLog
import pt.tiago.zerodroid.data.AppPhase
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.enroll.OidcLoginClient
import pt.tiago.zerodroid.enroll.OkHttpOidcHttp
import pt.tiago.zerodroid.security.KeystoreIdentity
import pt.tiago.zerodroid.security.buildCsrPem

/** Wires [OidcSignInViewModel] to the real gateway client, keystore and connection. */
fun oidcSignInViewModel(context: Context, mode: OidcMode): OidcSignInViewModel {
    val app = context.applicationContext
    val http = OkHttpOidcHttp()
    return OidcSignInViewModel(
        mode = mode,
        login = OidcLoginClient(http, csr = { buildCsrPem(KeystoreIdentity().getOrCreate()) }, nowSeconds = { System.currentTimeMillis() / 1000 }),
        http = http,
        probe = { profile, token -> ZeroDroidConnection.probe(profile, token) },
        persist = { profile, token -> ZeroDroidConnection.onPaired(profile, token) },
        renewed = { token, cert, pin -> ZeroDroidConnection.onReauthenticated(token, cert, pin) },
        activeProfile = { (ZeroDroidConnection.phase.value as? AppPhase.Paired)?.profile },
        deleteKey = { runCatching { KeystoreIdentity().delete() } },
        diagnose = { CrashLog.record(app, "oidc", it) },
    )
}

/**
 * ViewModelStore key for a renew sheet. Renew view models capture their profile, and the store
 * outlives an unpair: keying on the sign-in (device, gateway, token time) keeps a new sign-in from
 * reusing one that would renew against the previous gateway.
 */
fun renewViewModelKey(profile: pt.tiago.zerodroid.data.DaemonProfile, generation: Int): String =
    "oidc-renew-${profile.deviceId}-${profile.gatewayUrl}-${profile.tokenObtainedAt}-$generation"
