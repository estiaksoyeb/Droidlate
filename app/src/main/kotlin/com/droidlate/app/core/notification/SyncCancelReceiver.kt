package com.droidlate.app.core.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Job

/**
 * Controller managing cancellation of active sync coroutines.
 */
object SyncController {
    @Volatile
    private var activeSyncJob: Job? = null
    @Volatile
    private var onCancelCallback: (() -> Unit)? = null

    fun registerSync(job: Job, onCancel: () -> Unit) {
        activeSyncJob = job
        onCancelCallback = onCancel
    }

    fun cancelActiveSync() {
        activeSyncJob?.cancel()
        activeSyncJob = null
        onCancelCallback?.invoke()
        onCancelCallback = null
    }

    fun clear() {
        activeSyncJob = null
        onCancelCallback = null
    }
}

/**
 * BroadcastReceiver triggered by the "Stop Sync" notification action button.
 */
class SyncCancelReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CANCEL_SYNC = "com.droidlate.app.ACTION_CANCEL_SYNC"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_CANCEL_SYNC) {
            SyncController.cancelActiveSync()
            NotificationHelper.getInstance(context).cancel(NotificationHelper.ID_SYNC_ONGOING)
        }
    }
}
