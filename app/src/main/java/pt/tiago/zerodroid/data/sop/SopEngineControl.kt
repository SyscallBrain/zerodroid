package pt.tiago.zerodroid.data.sop

import kotlinx.serialization.json.JsonPrimitive
import pt.tiago.zerodroid.data.config.ConfigRepository

/** What it takes to turn the daemon's SOP engine on. */
sealed interface EngineFix {
    /** `sop.sops_dir` is unset: it has to be set in Config first. */
    data object Configure : EngineFix
    /**
     * `sop.sops_dir` is set but the engine is still off: the daemon only wires it at start, so it
     * needs a restart. [running] agent turns would be cut off by it.
     */
    data class Restart(val running: Int) : EngineFix
}

/** Decides between "configure" and "restart" for an engine that is off, and performs the restart. */
class SopEngineControl(private val config: ConfigRepository) {
    suspend fun fix(): EngineFix {
        val dir = runCatching { config.fields("sop") }.getOrDefault(emptyList())
            .firstOrNull { it.path == "sop.sops_dir" }
            ?.takeIf { it.populated }
            ?.let { (it.value as? JsonPrimitive)?.content?.trim() }
        if (dir.isNullOrEmpty()) return EngineFix.Configure
        return EngineFix.Restart(runCatching { config.runningTurns() }.getOrDefault(0))
    }

    /** Asks the daemon to restart; returns an error message, or null when it accepted. */
    suspend fun restart(): String? = config.reload()

    /** Restarts and waits for the connection to come back; returns an error message, or null when it's back. */
    suspend fun restartAndWait(awaitRestart: suspend () -> Boolean): String? {
        restart()?.let { return it }
        return if (awaitRestart()) null else "The daemon hasn't come back yet. Try again in a moment."
    }
}
