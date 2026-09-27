package cz.loplex.dogvision.swing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The system's dark or light, read from what dbus-send prints of the portal and `reg query` of the registry. */
class ThemeTest {
    @Test
    fun thePortalsColourSchemeIsReadFromReadOneAndFromRead() {
        assertEquals(1, portalColourScheme("   variant       uint32 1\n"))
        assertEquals(2, portalColourScheme("   variant       variant       uint32 2\n"))
        assertNull(portalColourScheme("Error org.freedesktop.DBus.Error.ServiceUnknown: The name is not activatable\n"))
    }

    @Test
    fun theRegistrySaysDarkWhereAppsUseNoLightTheme() {
        val key = "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize\r\n"
        assertTrue(registryPrefersDark("\r\n$key    AppsUseLightTheme    REG_DWORD    0x0\r\n\r\n"))
        assertFalse(registryPrefersDark("\r\n$key    AppsUseLightTheme    REG_DWORD    0x1\r\n\r\n"))
        assertFalse(
            registryPrefersDark("ERROR: The system was unable to find the specified registry key or value.\r\n"),
        )
    }
}
