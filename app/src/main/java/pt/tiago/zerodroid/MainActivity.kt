package pt.tiago.zerodroid

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import pt.tiago.zerodroid.data.AppPhase
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.enroll.EnrollClient
import pt.tiago.zerodroid.enroll.OkHttpEnrollHttp
import pt.tiago.zerodroid.security.KeystoreIdentity
import pt.tiago.zerodroid.security.buildCsrPem
import pt.tiago.zerodroid.service.Notifications
import pt.tiago.zerodroid.ui.OpenChat
import pt.tiago.zerodroid.ui.ZdApp
import pt.tiago.zerodroid.ui.onboarding.OnboardingViewModel

class MainActivity : ComponentActivity() {
    private lateinit var onboarding: OnboardingViewModel
    private var externalOpen by mutableStateOf<OpenChat?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        Perf.mark("activity.onCreate")
        super.onCreate(savedInstanceState)
        Perf.attach(this)

        val factory = viewModelFactory {
            initializer {
                val identity = KeystoreIdentity()
                OnboardingViewModel(
                    enroll = EnrollClient(OkHttpEnrollHttp()) { buildCsrPem(identity.getOrCreate()) },
                    persist = { profile, token -> ZeroDroidConnection.onPaired(profile, token) },
                    activeProfile = { (ZeroDroidConnection.phase.value as? AppPhase.Paired)?.profile },
                    diagnose = { CrashLog.record(applicationContext, "pairing", it) },
                )
            }
        }
        onboarding = ViewModelProvider(this, factory)[OnboardingViewModel::class.java]
        if (savedInstanceState == null) handleIntent(intent)

        val debug = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        setContent {
            ZdApp(
                onboarding = onboarding,
                debug = debug,
                externalOpen = externalOpen,
                onExternalOpenConsumed = { externalOpen = null },
                onScanQr = ::scanQr,
            )
        }
        Perf.mark("activity.setContent")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Deep link `zerodroid://pair?…` or a notification tap that names a chat. The link is cleared so it cannot replay. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val data = intent.data
        if (data != null && data.scheme == "zerodroid") {
            onboarding.onLink(data.toString())
            intent.data = null
            setIntent(Intent(intent).setData(null))
            return
        }
        val session = intent.getStringExtra(Notifications.EXTRA_SESSION)
        if (session != null) {
            externalOpen = OpenChat(session, intent.getStringExtra(Notifications.EXTRA_ALIAS) ?: "agent")
            intent.removeExtra(Notifications.EXTRA_SESSION)
        }
    }

    private fun scanQr() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnCanceledListener { }
            .addOnSuccessListener { code -> code.rawValue?.let(onboarding::onLink) }
            .addOnFailureListener { e ->
                CrashLog.record(applicationContext, "qr", "scanner unavailable: ${e.javaClass.simpleName}: ${e.message}")
                onboarding.onError("The QR scanner is not available on this phone. Paste or type the link instead.")
            }
    }

    override fun onStart() {
        super.onStart()
        ZeroDroidConnection.onForeground()
    }

    override fun onStop() {
        super.onStop()
        ZeroDroidConnection.onBackground()
        Perf.dump(this)
    }
}
