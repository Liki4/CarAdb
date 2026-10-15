package dev.caradb

import android.content.Context
import org.json.JSONObject

internal data class TunnelStatus(
    val state: String = "stopped",
    val lastHandshake: Long = 0,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val connections: Int = 0,
    val error: SafeError = SafeError.NONE,
    val retrySeconds: Int = 0,
) {
    val active: Boolean
        get() = state in setOf("starting", "waiting", "running", "stopping", "waiting_network", "retry_wait", "stop_failed")

    fun label(context: Context): String {
        if (state == "retry_wait") return context.getString(R.string.status_retry_wait, retrySeconds)
        return context.getString(
            when (state) {
                "starting" -> R.string.status_starting
                "waiting" -> R.string.status_waiting
                "running" -> R.string.status_running
                "stopping" -> R.string.status_stopping
                "waiting_network" -> R.string.status_waiting_network
                "error", "stop_failed" -> R.string.status_error
                else -> R.string.status_stopped
            },
        )
    }

    companion object {
        fun fromJson(text: String): TunnelStatus {
            val json = JSONObject(text)
            return TunnelStatus(
                state = json.getString("state"),
                lastHandshake = json.optLong("lastHandshake"),
                rxBytes = json.optLong("rxBytes"),
                txBytes = json.optLong("txBytes"),
                connections = json.optInt("connections"),
                // Do not expose even native error strings. State, not a nonempty error, drives retry.
                error = if (json.optString("error").isBlank()) SafeError.NONE else SafeError.CORE_WARNING,
            )
        }
    }
}
