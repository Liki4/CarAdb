package dev.caradb

import android.content.Context

internal object ConnectionPresentation {
    fun title(status: TunnelStatus, ready: Boolean, hasDraft: Boolean, writeFailed: Boolean): Int {
        if (!ready && !status.active) return R.string.ui_title_loading
        if (writeFailed && !status.active) return R.string.ui_title_write_failed
        return when (status.state) {
            "starting" -> R.string.ui_title_starting
            "waiting" -> R.string.ui_title_waiting
            "running" -> R.string.ui_title_running
            "waiting_network" -> R.string.ui_title_network
            "retry_wait" -> R.string.ui_title_retry
            "stopping" -> R.string.ui_title_stopping
            "stop_failed" -> R.string.ui_title_stop_failed
            "error" -> R.string.ui_title_error
            else -> if (hasDraft) R.string.ui_title_stopped else R.string.ui_title_empty
        }
    }

    fun message(context: Context, status: TunnelStatus, ready: Boolean, hasDraft: Boolean, writeFailed: Boolean): String {
        if (!ready && !status.active) return context.getString(R.string.ui_message_loading)
        if (writeFailed && !status.active) return context.getString(R.string.ui_message_write_failed)
        return when (status.state) {
            "starting" -> context.getString(R.string.ui_message_starting)
            "waiting" -> context.getString(R.string.ui_message_waiting)
            "running" -> context.getString(R.string.ui_message_running, handshakeAge(context, status.lastHandshake))
            "waiting_network" -> context.getString(R.string.ui_message_network)
            "retry_wait" -> context.getString(R.string.ui_message_retry, status.retrySeconds)
            "stopping" -> context.getString(R.string.ui_message_stopping)
            "stop_failed" -> context.getString(R.string.ui_message_stop_failed)
            "error" -> context.getString(R.string.ui_message_error)
            else -> context.getString(if (hasDraft) R.string.ui_message_stopped else R.string.ui_message_empty)
        }
    }

    fun indicator(status: TunnelStatus, writeFailed: Boolean): Int = when {
        writeFailed || status.state == "error" || status.state == "stop_failed" -> R.color.ui_status_error
        status.state in setOf("starting", "waiting", "waiting_network", "retry_wait") -> R.color.ui_status_warning
        status.state == "running" -> R.color.ui_accent
        else -> R.color.ui_text_secondary
    }

    fun handshakeAge(context: Context, timestamp: Long): String {
        if (timestamp <= 0) return context.getString(R.string.no_handshake)
        val seconds = (System.currentTimeMillis() / 1000 - timestamp).coerceAtLeast(0)
        return when {
            seconds < 5 -> context.getString(R.string.ui_age_now)
            seconds < 60 -> context.getString(R.string.ui_age_seconds, seconds)
            seconds < 3600 -> context.getString(R.string.ui_age_minutes, seconds / 60)
            seconds < 86400 -> context.getString(R.string.ui_age_hours, seconds / 3600)
            else -> context.getString(R.string.ui_age_days, seconds / 86400)
        }
    }

    // Display only: callers supply the controller's already-validated saved profile.
    // Never use this as a validator or fall back to displaying configuration text.
    fun tunnelAddress(config: String?): String? {
        val line = config?.lineSequence()
            ?.map { it.substringBefore('#').trim() }
            ?.firstOrNull { it.substringBefore('=').trim() == "Address" }
            ?: return null
        val address = line.substringAfter('=', "").trim().substringBefore('/')
        val octets = address.split('.')
        if (octets.size != 4) return null
        for (part in octets) {
            if (part.isEmpty() || part.any { it !in '0'..'9' }) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
        }
        return "$address:15555"
    }
}
