package pt.tiago.zerodroid.data.cron

import pt.tiago.zerodroid.rpc.proto.CronAddParams
import pt.tiago.zerodroid.rpc.proto.CronDelivery
import pt.tiago.zerodroid.rpc.proto.CronJob
import pt.tiago.zerodroid.rpc.proto.CronPatchParams

/**
 * The job editor's form. Plain strings as typed; [toAdd] and [toPatch] turn it into RPC params.
 * Type and agent are fixed once a job exists: the daemon has no patch for them.
 */
data class CronDraft(
    val name: String = "",
    val agent: String = "",
    val agentJob: Boolean = false,
    val expr: String = "",
    val tz: String = "",
    val command: String = "",
    val prompt: String = "",
    val model: String = "",
    val sessionMain: Boolean = false,
    /** Comma-separated tool names; blank = the agent's normal tool set. */
    val allowedTools: String = "",
    val usesMemory: Boolean = true,
    val announce: Boolean = false,
    val channel: String = "",
    val to: String = "",
    val bestEffort: Boolean = true,
    /** Carried, not edited: a thread or topic an agent set on the delivery. */
    val threadId: String = "",
    /** `cron`, `at` or `every`; only `cron` schedules are edited (the others have no expression). */
    val scheduleKind: String = "cron",
) {
    private val cronSchedule: Boolean get() = scheduleKind == "cron"

    fun problems(): List<String> = buildList {
        if (agent.isBlank()) add("Pick an agent")
        if (cronSchedule && expr.isBlank()) add("Enter a schedule")
        if (agentJob) { if (prompt.isBlank()) add("Enter a prompt") } else if (command.isBlank()) add("Enter a command")
        if (announce) {
            if (channel.isBlank()) add("Pick a channel")
            if (to.isBlank()) add("Enter who to send it to")
        }
    }

    fun dirty(original: CronDraft): Boolean = this != original

    fun toAdd(): CronAddParams = CronAddParams(
        agent = agent.trim(),
        schedule = expr.trim(),
        tz = tz.trim().ifEmpty { null },
        name = name.trim().ifEmpty { null },
        jobType = if (agentJob) "agent" else null,
        command = if (agentJob) null else command.trim(),
        prompt = if (agentJob) prompt.trim() else null,
        model = if (agentJob) model.trim().ifEmpty { null } else null,
        sessionTarget = if (agentJob) (if (sessionMain) "main" else "isolated") else null,
        allowedTools = if (agentJob) tools().ifEmpty { null } else null,
        usesMemory = if (agentJob) usesMemory else null,
        delivery = if (announce) delivery() else null,
    )

    /** Only what changed against [original]; null when nothing did. */
    fun toPatch(id: String, original: CronDraft): CronPatchParams? {
        val scheduleChanged = original.cronSchedule && (expr.trim() != original.expr.trim() || tz.trim() != original.tz.trim())
        val agentFields = original.agentJob
        val modelChanged = agentFields && model.trim() != original.model.trim()
        val toolsChanged = agentFields && tools() != original.tools()
        val deliveryChanged = announce != original.announce ||
            (announce && (channel.trim() != original.channel.trim() || to.trim() != original.to.trim() || bestEffort != original.bestEffort))
        val p = CronPatchParams(
            id = id,
            agent = original.agent,
            name = name.trim().takeIf { it != original.name.trim() },
            schedule = if (scheduleChanged) expr.trim() else null,
            tz = if (scheduleChanged) tz.trim().ifEmpty { null } else null,
            clearTz = if (scheduleChanged && tz.isBlank() && original.tz.isNotBlank()) true else null,
            command = if (!agentFields) command.trim().takeIf { it != original.command.trim() } else null,
            prompt = if (agentFields) prompt.trim().takeIf { it != original.prompt.trim() } else null,
            delivery = if (deliveryChanged) (if (announce) delivery() else CronDelivery("none")) else null,
            model = if (modelChanged) model.trim().ifEmpty { null } else null,
            clearModel = if (modelChanged && model.isBlank()) true else null,
            sessionTarget = if (agentFields && sessionMain != original.sessionMain) (if (sessionMain) "main" else "isolated") else null,
            allowedTools = if (toolsChanged) tools().ifEmpty { null } else null,
            clearAllowedTools = if (toolsChanged && tools().isEmpty()) true else null,
            usesMemory = if (agentFields && usesMemory != original.usesMemory) usesMemory else null,
        )
        return p.takeIf { it != CronPatchParams(id = id, agent = original.agent) }
    }

    private fun tools(): List<String> = allowedTools.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun delivery() = CronDelivery("announce", channel.trim(), to.trim(), bestEffort, threadId.ifEmpty { null })

    companion object {
        fun from(job: CronJob): CronDraft = CronDraft(
            name = job.name.orEmpty(),
            agent = job.agentAlias.orEmpty(),
            agentJob = job.isAgent,
            expr = job.schedule?.expr ?: job.expression,
            tz = job.schedule?.tz.orEmpty(),
            command = job.command,
            prompt = job.prompt.orEmpty(),
            model = job.model.orEmpty(),
            sessionMain = job.sessionTarget == "main",
            allowedTools = job.allowedTools.orEmpty().joinToString(", "),
            usesMemory = job.usesMemory,
            announce = job.delivery.mode.equals("announce", ignoreCase = true),
            channel = job.delivery.channel.orEmpty(),
            to = job.delivery.to.orEmpty(),
            bestEffort = job.delivery.bestEffort,
            threadId = job.delivery.threadId.orEmpty(),
            scheduleKind = job.schedule?.kind ?: "cron",
        )
    }
}
