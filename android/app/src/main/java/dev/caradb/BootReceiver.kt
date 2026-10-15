package dev.caradb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import java.util.concurrent.atomic.AtomicBoolean

/** Normal, post-unlock broadcasts only. No direct-boot storage, activity launch, alarms or wake lock. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (android.os.Build.VERSION.SDK_INT >= 24 && !context.getSystemService(UserManager::class.java).isUserUnlocked) return

        val pending = goAsync()
        val main = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val controller = TunnelController.get(context)
        var epoch = controller.token().generation
        val timeout = Runnable {
            if (finished.compareAndSet(false, true)) {
                controller.launchFailed(epoch, SafeError.RESTORE_TIMEOUT)
                pending.finish()
            }
        }
        main.postDelayed(timeout, 8000)
        epoch = controller.handleBroadcast(action, expired = { finished.get() }) {
            if (finished.compareAndSet(false, true)) {
                main.removeCallbacks(timeout)
                pending.finish()
            }
        }
    }
}
