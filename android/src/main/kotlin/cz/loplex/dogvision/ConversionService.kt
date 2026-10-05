package cz.loplex.dogvision

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cz.loplex.dogvision.core.percent
import cz.loplex.dogvision.texts.Str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Runs the conversion [Conversions] hands it,
 * in the foreground, with a notification of how far it has got and a button that cancels it;
 * once it ends, a notification of its own says how, as the app's message does.
 *
 * The service is of the type Android gives media processing from Android 15,
 * with six hours to finish, and data sync's before, whose type it takes from Android 10.
 */
class ConversionService : Service() {
    private val scope = MainScope()
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            job?.cancel()
            if (job == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val work = Conversions.take()
        if (work == null || job != null) {
            if (job == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val texts = texts
        val facts = texts.facts
        val starting = texts.get(Str.CONVERTING, percent(0.0, 0.0, facts))
        createChannel()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.cancel(ENDED)
        val first = notification(starting, done = 0)
        // Not ServiceCompat's: androidx.core 1.19 masks the type with Android 14's, which have no media processing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(PROGRESS, first, foregroundType())
        } else {
            startForeground(PROGRESS, first)
        }
        Conversions.say(starting)
        job = scope.launch {
            var shown = 0
            var ended: String? = null
            try {
                ended = withContext(work.dispatcher) {
                    work.run(this) { done ->
                        val message = texts.get(Str.CONVERTING, percent(done, done, facts))
                        Conversions.say(message)
                        // A notification a percent, not one a row of a photo.
                        val percent = (done * 100).roundToInt()
                        if (percent != shown) {
                            shown = percent
                            show(PROGRESS, notification(message, percent))
                        }
                    }
                }
                Conversions.say(ended)
            } catch (cancelled: CancellationException) {
                Conversions.say(null)
                throw cancelled
            } finally {
                Conversions.ended()
                // The ending is a notification of its own:
                // on a Redmi, the service's notification detached and updated at once kept its progress, ongoing.
                // The progress is cancelled as well, should an update of it come after the service's is removed.
                ServiceCompat.stopForeground(this@ConversionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                notifications.cancel(PROGRESS)
                ended?.let { show(ENDED, notification(it, done = null)) }
                job = null
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 gives a media processing service six hours; one that takes longer is cancelled. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun foregroundType(): Int = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

        else -> 0
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, texts.get(Str.CONVERSIONS), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * The notification of [message]:
     * with the share [done], in percent, and the button that cancels the conversion while it runs;
     * one that can be swiped away once it has ended, for null.\
     * Either opens the app.
     */
    private fun notification(message: String, done: Int?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_full_size)
            .setContentTitle(texts.get(Str.APP_NAME))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .apply {
                if (done != null) {
                    setOngoing(true)
                    setProgress(100, done, false)
                    val service = this@ConversionService
                    val cancel = PendingIntent.getService(service, 0, cancelling(service), PendingIntent.FLAG_IMMUTABLE)
                    addAction(R.drawable.ic_cancel, texts.get(Str.CANCEL_CONVERSION), cancel)
                } else {
                    setAutoCancel(true)
                }
            }
            .build()
    }

    /** Shows [notification] as the one of [id], where the app may post notifications at all. */
    private fun show(id: Int, notification: Notification) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    companion object {
        private const val CHANNEL = "conversions"

        /** The notifications of a conversion running, the service's own, and of how one ended. */
        private const val PROGRESS = 1
        private const val ENDED = 2

        private const val CANCEL = "cz.loplex.dogvision.CANCEL_CONVERSION"

        /** The intent that cancels the conversion running. */
        fun cancelling(context: Context): Intent = Intent(context, ConversionService::class.java).setAction(CANCEL)
    }
}
