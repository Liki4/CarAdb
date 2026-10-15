package dev.caradb

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dev.caradb.bridge.mobile.Mobile
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** The only owner of process-global Mobile lifecycle calls and all ConfigStore I/O. */
internal class TunnelController private constructor(context: Context) {
    internal data class Token(val generation: Long, val owner: Long, val startId: Int)
    internal data class Snapshot(
        val status: TunnelStatus = TunnelStatus(),
        val loaded: Boolean = false,
        val savedProfile: SavedProfile? = null,
        val bootAutoConnect: Boolean = false,
        val desiredRunning: Boolean = false,
        val storageError: SafeError = SafeError.NONE,
        val activeHost: String = "127.0.0.1",
        val generation: Long = 0,
    )
    private data class Owner(val id: Long, val service: TunnelService, val startId: Int = 0)
    private data class PendingProfile(val generation: Long, val profile: SavedProfile)

    private val app = context.applicationContext
    private val store = ConfigStore(app)
    private val main = Handler(Looper.getMainLooper())
    private val lifecycle = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val probes = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
    private val gate = Any()
    private var generation = 0L
    private var nextOwner = 0L
    private var owner: Owner? = null
    private var wanted = false
    private var restoreBlocked = false
    private var pendingProfile: PendingProfile? = null
    private var loadRequested = false
    private var retry: ScheduledFuture<*>? = null
    private var poll: ScheduledFuture<*>? = null
    private var networkChange: ScheduledFuture<*>? = null

    // These fields are touched only by the lifecycle executor.
    private var cachedRecord: ConfigStore.Record? = null
    private var cachedProfile: SavedProfile? = null
    private var sessionProfile: SavedProfile? = null
    private var nativeToken: Token? = null
    private var monitor: NetworkMonitor? = null
    private var monitorToken: Token? = null
    private var network: NetworkMonitor.Link? = null
    private var observedNetwork: NetworkMonitor.Link? = null
    private var retryAttempt = 0

    @Volatile var snapshot = Snapshot()
        private set

    fun token(): Token = synchronized(gate) { currentToken() }
    fun isCurrent(token: Token): Boolean = synchronized(gate) { matches(token) }
    fun hasWantedSession(): Boolean = synchronized(gate) { wanted || pendingProfile != null }
    private fun currentToken() = Token(generation, owner?.id ?: 0, owner?.startId ?: 0)
    private fun matches(token: Token) = token == currentToken()
    private fun isWanted(token: Token) = synchronized(gate) { matches(token) && wanted }
    private fun sameGeneration(value: Long) = synchronized(gate) { generation == value }

    fun attach(service: TunnelService): Long {
        val token = synchronized(gate) {
            owner = Owner(++nextOwner, service)
            cancelTimers()
            currentToken()
        }
        lifecycle.execute {
            // Binding alone never restores. If this replaces a dead owner, only clean its engine.
            if (isCurrent(token)) {
                stopMonitor()
                stopNative(token)
            }
        }
        return token.owner
    }

    fun detach(ownerId: Long) {
        val cleanup = synchronized(gate) {
            if (owner?.id != ownerId) return
            // Owner removal invalidates runtime tokens, not user intent. Queued Stop/terminal
            // flag writes, a newer draft, and a queued automatic launch all survive destruction.
            // Neither this cleanup nor a subsequent bind can execute the preserved intent.
            owner = null
            cancelTimers()
            snapshot = snapshot.copy(status = if (wanted) TunnelStatus(state = "starting") else TunnelStatus())
            currentToken()
        }
        lifecycle.execute {
            // A late old onDestroy must not unregister or stop a replacement owner's resources.
            if (isCurrent(cleanup)) {
                stopMonitor()
                stopNative(cleanup)
                sessionProfile = null
            }
        }
        // Do not clear desiredRunning, or shut down this process-wide executor.
    }

    /** Called by the visible Activity BEFORE requesting its immediate foreground-service start. */
    fun prepareConnect(config: String, host: String): Long = synchronized(gate) {
        generation++
        wanted = true
        restoreBlocked = false
        val profile = SavedProfile(config, host.trim().ifEmpty { "127.0.0.1" })
        pendingProfile = PendingProfile(generation, profile)
        cancelTimers()
        snapshot = snapshot.copy(
            status = TunnelStatus(state = "starting"), activeHost = profile.adbHost, generation = generation,
        )
        generation
    }

    /** Every Android startId gets a new token, even for a stale restore intent. */
    fun claimStart(ownerId: Long, startId: Int): Token? = synchronized(gate) {
        val current = owner ?: return null
        if (current.id != ownerId) return null
        owner = current.copy(startId = startId)
        cancelTimers()
        if (!restoreBlocked) snapshot = snapshot.copy(status = TunnelStatus(state = "starting"))
        currentToken()
    }

    fun serviceReady(token: Token, requestGeneration: Long?, stickyRestore: Boolean, explicitRestore: Boolean = false) {
        lifecycle.execute {
            if (!isCurrent(token)) return@execute
            // An old launch may adopt the *current* wanted command, but cannot rearm a stopped one.
            val allowStoredRestore = stickyRestore && synchronized(gate) { !restoreBlocked }
            val pending = synchronized(gate) {
                pendingProfile?.takeIf { matches(token) && wanted && it.generation == generation }
            }
            if (pending != null) {
                if (!validate(pending.profile)) {
                    terminal(token, SafeError.INVALID_CONFIG)
                    return@execute
                }
                if (!isWanted(token)) return@execute
                val record = try {
                    store.save(pending.profile, connect = true)
                } catch (failure: StoreFailure) {
                    terminal(token, failure.code)
                    return@execute
                }
                remember(record, pending.profile)
                if (!isWanted(token)) return@execute
                synchronized(gate) { if (matches(token)) pendingProfile = null }
                publishRecord(record, token)
                resume(token, pending.profile)
            } else {
                val canRestore = synchronized(gate) {
                    // Android can deliver a queued RESTORE into a new process. Its old epoch is
                    // not comparable to this process's counter. Only this untouched local intent
                    // may consult durable desiredRunning below; it never consults the boot toggle
                    // or arms the record. A local Connect/Stop or terminal failure excludes it.
                    val freshProcessRestore = explicitRestore && generation == 0L && pendingProfile == null
                    matches(token) && !restoreBlocked &&
                        (wanted || (allowStoredRestore && requestGeneration == null) || freshProcessRestore)
                }
                if (!canRestore) {
                    finishStopped(token)
                    return@execute
                }
                try {
                    val record = store.read()
                    publishRecord(record, token)
                    if (!record.desiredRunning) {
                        synchronized(gate) { if (matches(token)) wanted = false }
                        finishStopped(token)
                        return@execute
                    }
                    val profile = profile(record) ?: throw StoreFailure(SafeError.SAVE_REQUIRED)
                    synchronized(gate) { if (matches(token) && !restoreBlocked) wanted = true }
                    if (isWanted(token)) resume(token, profile)
                } catch (failure: StoreFailure) {
                    terminal(token, failure.code)
                }
            }
        }
    }

    /** Shared path for the UI and notification. Generation changes before any disk/native work. */
    fun requestStop() {
        val token = synchronized(gate) {
            generation++
            wanted = false
            restoreBlocked = true
            pendingProfile = null
            cancelTimers()
            snapshot = snapshot.copy(status = snapshot.status.copy(state = "stopping"), generation = generation)
            currentToken()
        }
        notifyState(token)
        lifecycle.execute {
            // Owner/startId may change while queued; only a newer user intent cancels the flag write.
            if (!sameGeneration(token.generation)) return@execute
            try {
                val record = store.flags { it.copy(desiredRunning = false, lastErrorCode = SafeError.NONE) }
                publishRecord(record, generation = token.generation)
            } catch (_: StoreFailure) {
                synchronized(gate) {
                    if (generation == token.generation) snapshot = snapshot.copy(storageError = SafeError.WRITE_FAILED)
                }
            }
            if (isCurrent(token)) finishStopped(token)
        }
    }

    fun launchFailed(expectedGeneration: Long, code: SafeError = SafeError.FOREGROUND_DENIED) {
        val token = synchronized(gate) {
            if (generation != expectedGeneration) return
            wanted = false
            restoreBlocked = true
            pendingProfile = null
            cancelTimers()
            snapshot = snapshot.copy(status = TunnelStatus(state = "error", error = code))
            currentToken()
        }
        lifecycle.execute { terminal(token, code) }
    }

    fun foregroundFailed(token: Token) {
        if (isCurrent(token)) launchFailed(token.generation)
    }

    fun loadSaved() {
        synchronized(gate) {
            if (loadRequested) return
            loadRequested = true
        }
        lifecycle.execute {
            try {
                val record = store.read()
                val profile = profile(record)
                remember(record, profile)
                publishRecord(record, loaded = true)
            } catch (failure: StoreFailure) {
                // Loading the UI never starts a service. Terminal storage errors preserve ciphertext.
                val current = token()
                synchronized(gate) { snapshot = snapshot.copy(loaded = true, savedProfile = null, storageError = failure.code) }
                terminal(current, failure.code)
            }
        }
    }

    fun save(config: String, host: String, result: (SafeError) -> Unit) {
        val token = token()
        val profile = SavedProfile(config, host.trim().ifEmpty { "127.0.0.1" })
        lifecycle.execute {
            val error = if (!validate(profile)) SafeError.INVALID_CONFIG else try {
                val record = store.save(profile, connect = false)
                remember(record, profile)
                publishRecord(record, loaded = true)
                SafeError.NONE
            } catch (failure: StoreFailure) {
                synchronized(gate) { snapshot = snapshot.copy(storageError = failure.code) }
                failure.code
            }
            postResult(token) { result(error) }
        }
    }

    fun setBootAutoConnect(enabled: Boolean, result: (SafeError) -> Unit) {
        val token = token()
        lifecycle.execute {
            val error = try {
                val record = store.read()
                if (enabled && profile(record) == null) throw StoreFailure(SafeError.SAVE_REQUIRED)
                val updated = store.flags { it.copy(bootAutoConnect = enabled) }
                publishRecord(updated)
                SafeError.NONE
            } catch (failure: StoreFailure) {
                synchronized(gate) { snapshot = snapshot.copy(storageError = failure.code) }
                if (failure.code in setOf(SafeError.KEY_UNAVAILABLE, SafeError.INVALID_CONFIG, SafeError.STORE_UNREADABLE)) {
                    terminal(token, failure.code)
                }
                failure.code
            }
            postResult(token) { result(error) }
        }
    }

    fun probe(host: String, result: (SafeError) -> Unit) {
        val token = token()
        try {
            probes.execute {
                if (!isCurrent(token)) return@execute
                val error = try { Mobile.probe(host); SafeError.NONE } catch (_: Exception) { SafeError.PROBE_FAILED }
                postResult(token) { result(error) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            postResult(token) { result(SafeError.PROBE_FAILED) }
        }
    }

    /** Receiver work only reads/merges outer flags and launches; it never waits for Keystore or Go. */
    fun handleBroadcast(action: String, expired: () -> Boolean, complete: () -> Unit): Long {
        val epoch = synchronized(gate) { generation }
        lifecycle.execute {
            val blockedPackageRestore = synchronized(gate) {
                action == Intent.ACTION_MY_PACKAGE_REPLACED && restoreBlocked
            }
            if (!sameGeneration(epoch) || expired() || blockedPackageRestore) {
                main.post(complete)
                return@execute
            }
            try {
                var record = store.read()
                if (!sameGeneration(epoch) || expired()) {
                    main.post(complete)
                    return@execute
                }
                if (action == Intent.ACTION_BOOT_COMPLETED) {
                    record = store.flags {
                        it.copy(
                            desiredRunning = it.bootAutoConnect && it.hasProfile,
                            lastErrorCode = if (it.bootAutoConnect && !it.hasProfile) SafeError.SAVE_REQUIRED else it.lastErrorCode,
                        )
                    }
                } else if (record.desiredRunning && !record.hasProfile) {
                    record = store.flags { it.copy(desiredRunning = false, lastErrorCode = SafeError.SAVE_REQUIRED) }
                }
                publishRecord(record, generation = epoch)
                if (!sameGeneration(epoch) || expired()) {
                    main.post(complete)
                    return@execute
                }
                if (!record.desiredRunning) {
                    if (action == Intent.ACTION_BOOT_COMPLETED) {
                        val cleanup = synchronized(gate) {
                            if (generation != epoch) return@synchronized null
                            wanted = false
                            restoreBlocked = true
                            cancelTimers()
                            currentToken()
                        }
                        if (cleanup != null) lifecycle.execute { if (isCurrent(cleanup)) finishStopped(cleanup) }
                    }
                    main.post(complete)
                    return@execute
                }
                synchronized(gate) {
                    if (generation == epoch && !expired() &&
                        (action == Intent.ACTION_BOOT_COMPLETED || !restoreBlocked)
                    ) {
                        wanted = true
                        restoreBlocked = false
                        snapshot = snapshot.copy(status = TunnelStatus(state = "starting"))
                    }
                }
                main.post {
                    val stillWanted = synchronized(gate) { generation == epoch && wanted && !restoreBlocked }
                    if (stillWanted && !expired()) {
                        try {
                            TunnelService.launchRestore(app, epoch)
                        } catch (_: Exception) {
                            launchFailed(epoch)
                        }
                    }
                    complete()
                }
            } catch (failure: StoreFailure) {
                launchFailed(epoch, failure.code)
                main.post(complete)
            }
        }
        return epoch
    }

    private fun resume(token: Token, profile: SavedProfile) {
        if (!isWanted(token)) return
        sessionProfile = profile
        synchronized(gate) {
            if (matches(token)) snapshot = snapshot.copy(activeHost = profile.adbHost, savedProfile = cachedProfile, loaded = true)
        }
        stopMonitor()
        try {
            monitorToken = token
            monitor = NetworkMonitor(app) { lifecycle.execute { onNetworkEvent(token) } }
            network = monitor!!.start()
            observedNetwork = network
        } catch (_: Exception) {
            terminal(token, SafeError.NETWORK_MONITOR_FAILED)
            return
        }
        beginNative(token)
    }

    private fun beginNative(token: Token) {
        if (!isWanted(token)) return
        synchronized(gate) { if (matches(token)) { retry?.cancel(false); retry = null; poll?.cancel(false); poll = null } }
        // Serial Stop -> Start also reconciles Go's actual phase after a fatal core error.
        if (!stopNative(token) || !isWanted(token)) return
        if (network == null) {
            updateStatus(token, TunnelStatus(state = "waiting_network"))
            return
        }
        val profile = sessionProfile ?: return
        updateStatus(token, TunnelStatus(state = "starting"))
        nativeToken = token
        try {
            Mobile.start(profile.config, profile.adbHost)
            // DNS may take five seconds. A newer Stop wins immediately in the UI, then runs here
            // after Start returns; never run an uncoordinated Stop thread against the singleton.
            if (!isWanted(token)) return
            retryAttempt = 0
            refreshNative(token)
        } catch (_: Exception) {
            if (isWanted(token)) retryLater(token)
        }
    }

    private fun refreshNative(token: Token) {
        if (!isWanted(token) || nativeToken != token) return
        val status = try { TunnelStatus.fromJson(Mobile.status()) } catch (_: Exception) { null }
        if (!isWanted(token)) return
        if (status == null || status.state !in setOf("waiting", "running")) {
            retryLater(token)
            return
        }
        // An ADB connection error or an old handshake is not a fatal engine state.
        updateStatus(token, status)
        synchronized(gate) {
            if (matches(token) && wanted) {
                poll?.cancel(false)
                poll = lifecycle.schedule({ refreshNative(token) }, 1, TimeUnit.SECONDS)
            }
        }
    }

    private fun retryLater(token: Token) {
        if (!isWanted(token)) return
        if (!stopNative(token) || !isWanted(token)) return
        try {
            network = monitor?.current()
            observedNetwork = network
            synchronized(gate) { if (matches(token)) { networkChange?.cancel(false); networkChange = null } }
        } catch (_: Exception) {
            terminal(token, SafeError.NETWORK_MONITOR_FAILED)
            return
        }
        if (network == null) {
            updateStatus(token, TunnelStatus(state = "waiting_network"))
            return
        }
        val delay = RETRY_SECONDS[retryAttempt.coerceAtMost(RETRY_SECONDS.lastIndex)]
        retryAttempt = (retryAttempt + 1).coerceAtMost(RETRY_SECONDS.lastIndex)
        updateStatus(token, TunnelStatus(state = "retry_wait", error = SafeError.ENGINE_FAILED, retrySeconds = delay))
        synchronized(gate) {
            if (matches(token) && wanted) {
                retry?.cancel(false)
                retry = lifecycle.schedule({ if (isWanted(token)) beginNative(token) }, delay.toLong(), TimeUnit.SECONDS)
            }
        }
    }

    private fun onNetworkEvent(token: Token) {
        if (!isWanted(token) || monitorToken != token) return
        val now = try { monitor?.current() } catch (_: Exception) {
            terminal(token, SafeError.NETWORK_MONITOR_FAILED)
            return
        }
        if (now == observedNetwork) return
        observedNetwork = now
        synchronized(gate) {
            if (!matches(token) || !wanted) return
            networkChange?.cancel(false)
            networkChange = lifecycle.schedule({
                if (!isWanted(token) || monitorToken != token) return@schedule
                val settled = try { monitor?.current() } catch (_: Exception) {
                    terminal(token, SafeError.NETWORK_MONITOR_FAILED)
                    return@schedule
                }
                observedNetwork = settled
                if (settled != network) {
                    network = settled
                    beginNative(token)
                }
            }, 2, TimeUnit.SECONDS)
        }
    }

    private fun stopNative(token: Token): Boolean {
        if (!isCurrent(token)) return false
        return try {
            Mobile.stop()
            nativeToken = null
            true
        } catch (_: Exception) {
            updateStatus(token, TunnelStatus(state = "stop_failed", error = SafeError.STOP_FAILED))
            false
        }
    }

    private fun finishStopped(token: Token) {
        if (!isCurrent(token)) return
        stopMonitor()
        synchronized(gate) { if (matches(token)) cancelTimers() }
        val stopped = stopNative(token)
        if (!isCurrent(token)) return
        if (stopped) {
            sessionProfile = null
            updateStatus(token, TunnelStatus())
            finishService(token)
        }
        // On an unexpected bridge Stop failure retain the foreground and a usable Disconnect action.
    }

    private fun terminal(token: Token, code: SafeError) {
        synchronized(gate) {
            // Disarming this intent must survive a changed owner/startId, just like manual Stop.
            // Runtime cleanup below still requires the full token, so it cannot stop a replacement.
            if (generation != token.generation) return
            wanted = false
            restoreBlocked = true
            pendingProfile = null
            cancelTimers()
            snapshot = snapshot.copy(status = TunnelStatus(state = "error", error = code))
        }
        try {
            val record = store.flags { it.copy(desiredRunning = false, lastErrorCode = code) }
            publishRecord(record, generation = token.generation)
        } catch (_: StoreFailure) {
            synchronized(gate) {
                if (generation == token.generation) snapshot = snapshot.copy(storageError = SafeError.WRITE_FAILED)
            }
        }
        if (!isCurrent(token)) return
        stopMonitor()
        if (stopNative(token)) {
            sessionProfile = null
            notifyState(token)
            finishService(token)
        }
    }

    private fun stopMonitor() {
        monitor?.stop()
        monitor = null
        monitorToken = null
        network = null
        observedNetwork = null
    }

    // Call only under gate. Cancellation is immediate even while native DNS owns the executor.
    private fun cancelTimers() {
        retry?.cancel(false); retry = null
        poll?.cancel(false); poll = null
        networkChange?.cancel(false); networkChange = null
    }

    private fun validate(profile: SavedProfile): Boolean = try {
        Mobile.validate(profile.config, profile.adbHost)
        true
    } catch (_: Exception) { false }

    private fun profile(record: ConfigStore.Record): SavedProfile? {
        if (cachedRecord?.iv == record.iv && cachedRecord?.ciphertext == record.ciphertext && cachedProfile != null) {
            return cachedProfile
        }
        val profile = store.decrypt(record)
        if (profile != null && !validate(profile)) throw StoreFailure(SafeError.INVALID_CONFIG)
        remember(record, profile)
        return profile
    }

    private fun remember(record: ConfigStore.Record, profile: SavedProfile?) {
        cachedRecord = record
        cachedProfile = profile
    }

    private fun publishRecord(record: ConfigStore.Record, token: Token? = null, generation: Long? = null, loaded: Boolean = false) {
        synchronized(gate) {
            if (token != null && !matches(token)) return
            if (generation != null && this.generation != generation) return
            snapshot = snapshot.copy(
                bootAutoConnect = record.bootAutoConnect,
                desiredRunning = record.desiredRunning,
                storageError = record.lastErrorCode,
                savedProfile = cachedProfile,
                loaded = snapshot.loaded || loaded,
            )
        }
    }

    private fun updateStatus(token: Token, status: TunnelStatus) {
        synchronized(gate) { if (!matches(token)) return; snapshot = snapshot.copy(status = status) }
        notifyState(token)
    }

    private fun notifyState(token: Token) {
        main.post {
            synchronized(gate) {
                if (matches(token)) owner?.service?.updateNotification(token, snapshot.status)
            }
        }
    }

    private fun finishService(token: Token) {
        main.post {
            synchronized(gate) {
                if (matches(token)) owner?.service?.finishForeground(token)
            }
        }
    }

    private fun postResult(token: Token, result: () -> Unit) {
        main.post { if (isCurrent(token)) result() }
    }

    companion object {
        private val RETRY_SECONDS = intArrayOf(2, 4, 8, 16, 30, 60)
        @Volatile private var instance: TunnelController? = null
        fun get(context: Context): TunnelController = instance ?: synchronized(this) {
            instance ?: TunnelController(context).also { instance = it }
        }
    }
}
