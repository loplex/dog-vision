package cz.loplex.dogvision.web

import cz.loplex.dogvision.core.ClockTime
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import kotlin.js.Date

/**
 * How long a download's object URL is kept:
 * a browser may still be reading it after the click, and revoking it at once can cancel the download.
 */
private const val DOWNLOAD_URL_LIFETIME_MS = 60_000

/** The time on the local clock now, which names what is saved. */
fun now(): ClockTime = Date().run {
    ClockTime(getFullYear(), getMonth() + 1, getDate(), getHours(), getMinutes(), getSeconds())
}

/** Hands [blob] to the browser to save as a file called [name]. */
fun download(blob: Blob, name: String) {
    val url = URL.createObjectURL(blob)
    val link = document.createElement("a") as HTMLAnchorElement
    link.href = url
    link.download = name
    link.click()
    window.setTimeout({ URL.revokeObjectURL(url) }, DOWNLOAD_URL_LIFETIME_MS)
}
