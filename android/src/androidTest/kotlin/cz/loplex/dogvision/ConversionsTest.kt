package cz.loplex.dogvision

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.loplex.dogvision.core.percent
import cz.loplex.dogvision.texts.Str
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A conversion runs in ConversionService, apart from any screen:
 * its notification shows how far it has got and cancels it, and once it ends, says how, as its message does.
 */
@RunWith(AndroidJUnit4::class)
class ConversionsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val notifications = context.getSystemService(NotificationManager::class.java)

    /** Lets each conversion go on to its end, once completed. */
    private val release = CompletableDeferred<Unit>()

    @Before
    fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        notifications.cancelAll()
    }

    @After
    fun endTheConversion() {
        release.complete(Unit)
        waitFor("the conversion to end") { !Conversions.running.value }
        notifications.cancelAll()
    }

    /** Starts a conversion that reports half done and then waits for [release] before it returns "done". */
    private fun startHalfDone() = instrumentation.runOnMainSync {
        Conversions.start(context, Dispatchers.Default) { report ->
            report(0.5)
            release.await()
            "done"
        }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            assertTrue("waited 10 s for $what", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(50)
        }
    }

    /** The app's notification shown, if any. */
    private fun shown(): Notification? = notifications.activeNotifications.singleOrNull()?.notification

    private val halfDone = context.texts.let { it.get(Str.CONVERTING, percent(0.5, 0.5, it.facts)) }

    @Test
    fun theNotificationShowsHowFarItHasGotAndThenHowItEnded() {
        startHalfDone()
        waitFor("half done in the notification") {
            shown()?.extras?.getInt(Notification.EXTRA_PROGRESS) == 50
        }
        assertTrue(Conversions.running.value)
        assertEquals(halfDone, Conversions.message.value)
        assertEquals(halfDone, shown()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertTrue("ongoing while it runs", shown()!!.flags and Notification.FLAG_ONGOING_EVENT != 0)
        release.complete(Unit)
        waitFor("the conversion to end") { !Conversions.running.value }
        assertEquals("done", Conversions.message.value)
        waitFor("the ending in the notification") {
            shown()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "done"
        }
        assertFalse("ongoing once it ended", shown()!!.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun theNotificationsButtonCancelsIt() {
        startHalfDone()
        waitFor("the notification's button") { shown()?.actions?.size == 1 }
        shown()!!.actions.single().actionIntent.send()
        waitFor("the conversion to be cancelled") { !Conversions.running.value }
        assertNull(Conversions.message.value)
        waitFor("the notification to go") { shown() == null }
    }

    @Test
    fun aSecondConversionWhileOneRunsIsNotStarted() {
        startHalfDone()
        waitFor("the first conversion to run") { Conversions.message.value == halfDone }
        var started = false
        instrumentation.runOnMainSync {
            Conversions.start(context, Dispatchers.Default) {
                started = true
                "second"
            }
        }
        release.complete(Unit)
        waitFor("the conversion to end") { !Conversions.running.value }
        assertEquals("done", Conversions.message.value)
        assertFalse(started)
    }
}
