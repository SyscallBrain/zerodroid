package pt.tiago.zerodroid

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.service.Notifications
import pt.tiago.zerodroid.service.TurnKeepAliveService

class App : Application() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Perf.init(this)
        pt.tiago.zerodroid.rpc.RpcDecode.context = Dispatchers.Default
        CrashLog.install(this)
        Perf.span("app.connection.init") { ZeroDroidConnection.init(this) }
        Perf.span("app.notifications") {
            Notifications.createChannels(this)
            Notifications.watchApprovals(this, scope)
            Notifications.watchFinishedTurns(this, scope)
        }
        // Typefaces (Inter variable instances, JetBrains Mono) are otherwise created on the main thread
        // during the first frame; the resolver shares its cache with the one composition uses.
        CoroutineScope(Dispatchers.Default).launch {
            Perf.span("app.fontPreload") {
                val resolver = androidx.compose.ui.text.font.createFontFamilyResolver(this@App)
                runCatching { resolver.preload(pt.tiago.zerodroid.ui.theme.ZcFontFamily) }
                runCatching { resolver.preload(pt.tiago.zerodroid.ui.theme.ZcMonoFamily) }
            }
            Perf.mark("app.fontsReady")
        }
        Perf.mark("app.onCreate.end")
        scope.launch {
            ZeroDroidConnection.holdForeground.collect { hold ->
                if (hold) TurnKeepAliveService.start(this@App) else TurnKeepAliveService.stop(this@App)
            }
        }
    }
}
