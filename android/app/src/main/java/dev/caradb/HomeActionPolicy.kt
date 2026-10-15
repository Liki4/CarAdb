package dev.caradb

internal enum class PrimaryAction { CONNECT, CONFIGURE, DISCONNECT }

internal data class HomeActions(
    val primary: PrimaryAction,
    val primaryEnabled: Boolean,
    val secondaryDisconnect: Boolean,
)

internal object HomeActionPolicy {
    fun choose(
        active: Boolean,
        stopping: Boolean,
        storageWriteFailed: Boolean,
        hasDraft: Boolean,
        canConnect: Boolean,
        canDisconnect: Boolean,
    ): HomeActions {
        val stopPrimary = active || stopping || storageWriteFailed
        val primary = when {
            stopPrimary -> PrimaryAction.DISCONNECT
            hasDraft -> PrimaryAction.CONNECT
            else -> PrimaryAction.CONFIGURE
        }
        return HomeActions(
            primary = primary,
            primaryEnabled = if (stopPrimary) canDisconnect else canConnect,
            secondaryDisconnect = !stopPrimary && canDisconnect,
        )
    }
}
