package cz.loplex.dogvision

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one conversion of a photo or a video at its full size there is,
 * run by [ConversionService] rather than in the screen's ViewModel, whose scope Android ends with the screen:
 * it goes on while the app is in the background, or swiped away from the recent apps,
 * and its notification shows how far it has got and cancels it.
 *
 * It is [running] from [start] until its work returns, fails or is cancelled.\
 * [message] is what it is at, "Converting: 42%", then the message its work returns; null once it is cancelled.
 */
object Conversions {
    /** What a conversion does, on [dispatcher]: [run] reports the share done and returns the message it ends with. */
    internal class Work(
        val dispatcher: CoroutineDispatcher,
        val run: suspend CoroutineScope.(report: (Double) -> Unit) -> String,
    )

    private val mutableRunning = MutableStateFlow(false)
    val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

    private val mutableMessage = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = mutableMessage.asStateFlow()

    /** The work [start] asked for, which the service takes as it starts; touched on the main thread only. */
    private var pending: Work? = null

    /**
     * Starts [run] on [dispatcher] in the service, unless a conversion runs already.\
     * Called on the main thread.
     */
    fun start(
        context: Context,
        dispatcher: CoroutineDispatcher,
        run: suspend CoroutineScope.(report: (Double) -> Unit) -> String,
    ) {
        if (mutableRunning.value) return
        mutableRunning.value = true
        pending = Work(dispatcher, run)
        ContextCompat.startForegroundService(context, Intent(context, ConversionService::class.java))
    }

    /** Cancels the conversion running, if any; what it wrote so far is not saved. */
    fun cancel(context: Context) {
        if (mutableRunning.value) context.startService(ConversionService.cancelling(context))
    }

    internal fun take(): Work? = pending.also { pending = null }

    internal fun say(message: String?) {
        mutableMessage.value = message
    }

    internal fun ended() {
        mutableRunning.value = false
    }
}
