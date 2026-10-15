package dev.caradb

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.format.Formatter
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private enum class Section { CONNECTION, CONFIG, SETTINGS }

    private lateinit var controller: TunnelController
    private lateinit var config: EditText
    private lateinit var host: EditText
    private lateinit var save: Button
    private lateinit var connect: Button
    private lateinit var disconnect: Button
    private lateinit var cancelRestore: Button
    private lateinit var probe: Button
    private lateinit var boot: Switch
    private lateinit var status: TextView
    private lateinit var message: TextView
    private lateinit var storage: TextView
    private lateinit var profileBadge: TextView
    private lateinit var entryAddress: TextView
    private lateinit var localAddress: TextView
    private lateinit var actionCaption: TextView
    private lateinit var bootShortcut: Button
    private lateinit var battery: TextView
    private lateinit var probeResult: TextView
    private lateinit var errorViews: List<TextView>
    private lateinit var sections: Map<Section, View>
    private lateinit var navigation: Map<Section, Button>
    private var selectedSection = Section.CONNECTION
    private var homeActions = HomeActions(PrimaryAction.CONFIGURE, false, false)
    private var detailsDialog: AlertDialog? = null
    private var detailsText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var bound = false
    private var visible = false
    private var resumed = false
    private var inputsRestored = false
    private var updatingBoot = false
    private var saving = false
    private var changingBoot = false
    private var probing = false
    private var uiEpoch = 0L
    private var saveToken: TunnelController.Token? = null
    private var bootToken: TunnelController.Token? = null
    private var probeToken: TunnelController.Token? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            controller = (binder as TunnelService.LocalBinder).controller
            render()
        }
        override fun onServiceDisconnected(name: ComponentName) = render()
    }

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)
        controller = TunnelController.get(this)
        config = findViewById(R.id.config)
        host = findViewById(R.id.host)
        save = findViewById(R.id.save)
        connect = findViewById(R.id.connect)
        disconnect = findViewById(R.id.disconnect)
        cancelRestore = findViewById(R.id.cancel_restore)
        probe = findViewById(R.id.probe)
        boot = findViewById(R.id.boot_auto_connect)
        status = findViewById(R.id.status)
        message = findViewById(R.id.status_message)
        storage = findViewById(R.id.storage_status)
        profileBadge = findViewById(R.id.profile_badge)
        entryAddress = findViewById(R.id.tunnel_address)
        localAddress = findViewById(R.id.home_local_host)
        actionCaption = findViewById(R.id.action_caption)
        bootShortcut = findViewById(R.id.boot_shortcut)
        battery = findViewById(R.id.battery_status)
        probeResult = findViewById(R.id.probe_result)
        errorViews = listOf(R.id.error, R.id.config_error, R.id.settings_error).map { findViewById(it) }
        sections = mapOf(
            Section.CONNECTION to findViewById(R.id.page_connection),
            Section.CONFIG to findViewById(R.id.page_config),
            Section.SETTINGS to findViewById(R.id.page_settings),
        )
        navigation = mapOf(
            Section.CONNECTION to findViewById(R.id.nav_connection),
            Section.CONFIG to findViewById(R.id.nav_config),
            Section.SETTINGS to findViewById(R.id.nav_settings),
        )
        config.isEnabled = false
        host.isEnabled = false
        config.setSaveFromParentEnabled(false)
        host.setSaveFromParentEnabled(false)
        if (Build.VERSION.SDK_INT >= 26) {
            findViewById<View>(R.id.page).importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        findViewById<View>(R.id.page).setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                CarUiLayout.apply(this, insets.isVisible(WindowInsets.Type.ime()))
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        CarUiLayout.apply(this)
        navigation.forEach { (section, button) -> button.setOnClickListener { showSection(section) } }
        val restoredSection = savedInstanceState?.getString(SECTION_STATE)
        selectedSection = Section.entries.firstOrNull { it.name == restoredSection } ?: Section.CONNECTION
        showSection(selectedSection)

        save.setOnClickListener { saveProfile() }
        connect.setOnClickListener {
            render()
            if (connect.isEnabled && connect.visibility == View.VISIBLE) {
                if (homeActions.primary == PrimaryAction.CONFIGURE) showSection(Section.CONFIG)
                else startTunnel()
            }
        }
        disconnect.setOnClickListener { requestStop() }
        cancelRestore.setOnClickListener { requestStop() }
        bootShortcut.setOnClickListener { showSection(Section.SETTINGS) }
        findViewById<Button>(R.id.show_details).setOnClickListener { showDetails() }
        probe.setOnClickListener { probeLocalAdb() }
        boot.setOnCheckedChangeListener { _, enabled ->
            if (!updatingBoot) changeBoot(enabled)
        }
        findViewById<Button>(R.id.battery_settings).setOnClickListener { openBatterySettings() }
        controller.loadSaved()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Only navigation is saved here; sensitive editor drafts are not serialized.
        outState.putString(SECTION_STATE, selectedSection.name)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        visible = true
        bound = bindService(Intent(this, TunnelService::class.java), connection, BIND_AUTO_CREATE)
        handler.post(refresh)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        battery.setText(
            if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
                R.string.battery_unrestricted else R.string.battery_restricted,
        )
        render()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStop() {
        visible = false
        uiEpoch++
        handler.removeCallbacks(refresh)
        detailsDialog?.dismiss()
        if (bound) unbindService(connection)
        bound = false
        probing = false
        saving = false
        changingBoot = false
        super.onStop()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (selectedSection != Section.CONNECTION) showSection(Section.CONNECTION)
        else super.onBackPressed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            showMessage(getString(R.string.notification_denied))
        }
        // Permission callbacks never initiate a delayed/background FGS start.
    }

    private fun showSection(section: Section) {
        if (section != Section.CONFIG) {
            currentFocus?.takeIf { it === config || it === host }?.let { focused ->
                getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(focused.windowToken, 0)
                focused.clearFocus()
            }
        }
        selectedSection = section
        sections.forEach { (key, view) -> view.visibility = if (key == section) View.VISIBLE else View.GONE }
        navigation.forEach { (key, button) -> button.isSelected = key == section }
        findViewById<TextView>(R.id.page_title).setText(
            when (section) {
                Section.CONNECTION -> R.string.ui_page_connection
                Section.CONFIG -> R.string.ui_page_config
                Section.SETTINGS -> R.string.ui_page_settings
            },
        )
        render()
    }

    private fun startTunnel() {
        if (!resumed || !inputsRestored) return
        if (config.text.isBlank()) {
            showSection(Section.CONFIG)
            showMessage(getString(R.string.config_required))
            return
        }
        val epoch = controller.prepareConnect(config.text.toString(), host.text.toString())
        try {
            // Request while visible, not after an asynchronous save or a permission dialog.
            TunnelService.launchConnect(this, epoch)
        } catch (_: Exception) {
            controller.launchFailed(epoch)
            showMessage(getString(R.string.error_foreground_denied))
            render()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION)
            } catch (_: Exception) {
                showMessage(getString(R.string.notification_denied))
            }
        }
        render()
    }

    private fun requestStop() {
        val current = controller.snapshot
        if (current.status.state == "stopping" ||
            !(current.status.active || current.desiredRunning || current.storageError == SafeError.WRITE_FAILED)
        ) return
        controller.requestStop()
        if (controller.snapshot.bootAutoConnect) showMessage(getString(R.string.stop_keeps_boot))
        render()
    }

    private fun saveProfile() {
        saving = true
        saveToken = controller.token()
        val epoch = uiEpoch
        controller.save(config.text.toString(), host.text.toString()) { problem ->
            if (!visible || epoch != uiEpoch || isDestroyed) return@save
            saving = false
            showMessage(getString(if (problem == SafeError.NONE) R.string.profile_saved else problem.messageRes))
            render()
        }
        render()
    }

    private fun changeBoot(enabled: Boolean) {
        changingBoot = true
        bootToken = controller.token()
        val epoch = uiEpoch
        controller.setBootAutoConnect(enabled) { problem ->
            if (!visible || epoch != uiEpoch || isDestroyed) return@setBootAutoConnect
            changingBoot = false
            if (problem != SafeError.NONE) showMessage(getString(problem.messageRes))
            render()
        }
        render()
    }

    private fun probeLocalAdb() {
        probing = true
        probeToken = controller.token()
        probeResult.setText(R.string.probe_working)
        val snapshot = controller.snapshot
        val target = if (snapshot.status.active) snapshot.activeHost else host.text.toString().trim()
        val epoch = uiEpoch
        controller.probe(target) { problem ->
            if (!visible || epoch != uiEpoch || isDestroyed) return@probe
            probing = false
            probeResult.setText(if (problem == SafeError.NONE) R.string.probe_ok else R.string.probe_failed)
            render()
        }
        render()
    }

    private fun render() {
        val current = controller.snapshot
        val snapshot = current.status
        if (!inputsRestored && current.loaded) {
            // Restore once; navigation and later snapshots must not replace an edited draft.
            config.setText(current.savedProfile?.config.orEmpty())
            host.setText(if (snapshot.active) current.activeHost else current.savedProfile?.adbHost ?: "127.0.0.1")
            inputsRestored = true
        }
        if (saving && saveToken?.let { !controller.isCurrent(it) } == true) saving = false
        if (changingBoot && bootToken?.let { !controller.isCurrent(it) } == true) changingBoot = false
        if (probing && probeToken?.let { !controller.isCurrent(it) } == true) {
            probing = false
            probeResult.text = ""
        }
        val editable = inputsRestored && !snapshot.active && !saving
        val canConnect = editable && resumed
        val writeFailed = current.storageError == SafeError.WRITE_FAILED
        val canDisconnect = snapshot.state != "stopping" && (snapshot.active || current.desiredRunning || writeFailed)
        val hasDraft = config.text.isNotBlank()
        homeActions = HomeActionPolicy.choose(
            snapshot.active, snapshot.state == "stopping", writeFailed,
            hasDraft, canConnect, canDisconnect,
        )
        connect.visibility = if (homeActions.primary == PrimaryAction.DISCONNECT) View.GONE else View.VISIBLE
        disconnect.visibility = if (homeActions.primary == PrimaryAction.DISCONNECT) View.VISIBLE else View.GONE
        connect.isEnabled = homeActions.primaryEnabled && homeActions.primary != PrimaryAction.DISCONNECT
        disconnect.isEnabled = canDisconnect
        connect.setText(if (homeActions.primary == PrimaryAction.CONFIGURE) R.string.ui_configure else R.string.ui_connect)
        disconnect.setText(disconnectLabel(snapshot, writeFailed))
        cancelRestore.visibility = if (homeActions.secondaryDisconnect) View.VISIBLE else View.GONE
        cancelRestore.isEnabled = canDisconnect
        actionCaption.setText(
            when {
                homeActions.secondaryDisconnect -> R.string.ui_action_note_cancel_recovery
                homeActions.primary == PrimaryAction.CONFIGURE -> R.string.ui_action_note_configure
                homeActions.primary == PrimaryAction.CONNECT -> R.string.ui_action_note_connect
                current.bootAutoConnect -> R.string.ui_action_note_stop_boot_on
                else -> R.string.ui_action_note_stop_boot_off
            },
        )
        setTextIfChanged(status, getString(ConnectionPresentation.title(snapshot, inputsRestored, hasDraft, writeFailed)))
        setTextIfChanged(message, ConnectionPresentation.message(this, snapshot, inputsRestored, hasDraft, writeFailed))
        findViewById<View>(R.id.status_indicator).backgroundTintList = ColorStateList.valueOf(getColor(ConnectionPresentation.indicator(snapshot, writeFailed)))
        val problems = listOf(snapshot.error, current.storageError).filter { it != SafeError.NONE }.distinct()
        val problemText = problems.joinToString("\n") { getString(it.messageRes) }
        errorViews.forEach {
            setTextIfChanged(it, problemText)
            it.visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE
        }
        storage.setText(
            when {
                !current.loaded -> R.string.profile_loading
                current.savedProfile != null -> R.string.profile_available
                else -> R.string.profile_missing
            },
        )
        val saved = current.savedProfile
        val draftChanged = inputsRestored && (config.text.toString() != saved?.config.orEmpty() ||
            normalizedHost() != (saved?.adbHost ?: "127.0.0.1"))
        profileBadge.setText(
            when {
                !current.loaded -> R.string.ui_badge_loading
                current.storageError != SafeError.NONE -> R.string.ui_badge_error
                draftChanged -> R.string.ui_badge_unsaved
                saved != null -> R.string.ui_badge_saved
                else -> R.string.ui_badge_missing
            },
        )
        val endpoint = currentTunnelEntry(current)
        setTextIfChanged(entryAddress, if (endpoint == null) getString(R.string.ui_tunnel_port) else getString(R.string.ui_tunnel_address, endpoint))
        if (snapshot.active && inputsRestored && host.text.toString() != current.activeHost) host.setText(current.activeHost)
        setTextIfChanged(localAddress, "${if (snapshot.active) current.activeHost else normalizedHost()}:5555")
        bootShortcut.setText(if (current.bootAutoConnect) R.string.ui_boot_on else R.string.ui_boot_off)
        findViewById<View>(R.id.config_readonly).visibility = if (snapshot.active) View.VISIBLE else View.GONE
        config.isEnabled = editable
        host.isEnabled = editable
        save.isEnabled = editable
        probe.isEnabled = inputsRestored && !probing && snapshot.state != "stopping"
        updatingBoot = true
        boot.isChecked = current.bootAutoConnect
        updatingBoot = false
        boot.isEnabled = inputsRestored && !changingBoot && !saving && (saved != null || current.bootAutoConnect)
        updateDetails(current)
    }

    private fun disconnectLabel(status: TunnelStatus, writeFailed: Boolean): Int = when {
        status.state == "stopping" -> R.string.ui_stopping
        status.state == "stop_failed" || writeFailed -> R.string.ui_stop_again
        status.state == "starting" -> R.string.ui_cancel_connect
        status.state == "waiting_network" -> R.string.ui_stop_waiting
        status.state == "retry_wait" -> R.string.ui_stop_retry
        else -> R.string.ui_disconnect
    }

    private fun normalizedHost(): String = host.text.toString().trim().ifEmpty { "127.0.0.1" }

    private fun currentTunnelEntry(current: TunnelController.Snapshot): String? {
        val profile = current.savedProfile ?: return null
        // An unsaved draft is not yet the validated connection configuration.
        if (!current.status.active && config.text.toString() != profile.config) return null
        return ConnectionPresentation.tunnelAddress(profile.config)
    }

    private fun showDetails() {
        if (!resumed || detailsDialog?.isShowing == true) return
        val content = layoutInflater.inflate(R.layout.dialog_details, null)
        detailsText = content.findViewById(R.id.details)
        val gap = resources.getDimensionPixelSize(R.dimen.ui_gap)
        val title = TextView(this).apply {
            setText(R.string.ui_details_title)
            setTextColor(getColor(R.color.ui_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.ui_body_text) * 1.2f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(gap, gap, gap, gap / 2)
        }
        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(title)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener { detailsDialog = null; detailsText = null }
        detailsDialog = dialog
        updateDetails(controller.snapshot)
        dialog.show()
        val availableWidth = findViewById<View>(R.id.page).width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        dialog.window?.setLayout(
            minOf(resources.getDimensionPixelSize(R.dimen.ui_dialog_width), (availableWidth * .9f).toInt()),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            minimumHeight = resources.getDimensionPixelSize(R.dimen.ui_control_height)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.ui_body_text))
        }
    }

    private fun updateDetails(current: TunnelController.Snapshot) {
        val text = detailsText ?: return
        val snapshot = current.status
        val host = if (snapshot.active) current.activeHost else normalizedHost()
        val entry = currentTunnelEntry(current) ?: ":15555"
        val body = getString(
            R.string.status_details,
            ConnectionPresentation.handshakeAge(this, snapshot.lastHandshake),
            Formatter.formatShortFileSize(this, snapshot.rxBytes),
            Formatter.formatShortFileSize(this, snapshot.txBytes), snapshot.connections,
        ) + getString(R.string.ui_details_route, entry, "$host:5555")
        setTextIfChanged(text, body)
    }

    private fun setTextIfChanged(view: TextView, text: String) {
        if (view.text.toString() != text) view.text = text
    }

    private fun openBatterySettings() {
        val intents = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
                // Some head units omit the normal battery settings page. Try ordinary app settings.
            }
        }
        showMessage(getString(R.string.settings_unavailable))
    }

    private fun showMessage(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val NOTIFICATION_PERMISSION = 1
        private const val SECTION_STATE = "ui.section"
    }
}
