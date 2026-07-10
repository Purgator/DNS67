package fr.arichard.adblocker

import fr.arichard.adblocker.core.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun newerVersionsAreDetected() {
        assertTrue(UpdateManager.isNewer("1.9", "1.8"))
        assertTrue(UpdateManager.isNewer("2.0", "1.9"))
        assertTrue(UpdateManager.isNewer("1.10", "1.9"))   // numeric, not lexicographic
        assertTrue(UpdateManager.isNewer("1.8.1", "1.8"))
        assertTrue(UpdateManager.isNewer("10.0", "9.9"))
    }

    @Test
    fun equalOrOlderVersionsAreNot() {
        assertFalse(UpdateManager.isNewer("1.8", "1.8"))
        assertFalse(UpdateManager.isNewer("1.8", "1.9"))
        assertFalse(UpdateManager.isNewer("1.9", "1.10"))
        assertFalse(UpdateManager.isNewer("1.8", "1.8.1"))
        assertFalse(UpdateManager.isNewer("0.9", "1.0"))
    }

    @Test
    fun garbageRemoteNeverTriggersAnUpdate() {
        assertFalse(UpdateManager.isNewer("", "1.8"))
        assertFalse(UpdateManager.isNewer("abc", "1.8"))
        // Unreadable local version parses as 0, so a valid remote wins — desired recovery.
        assertTrue(UpdateManager.isNewer("1.8", ""))
    }

    @Test
    fun compareVersionsOrdersNumerically() {
        // Used to pick the highest already-downloaded pending APK.
        assertTrue(UpdateManager.compareVersions("1.15", "1.9") > 0)
        assertTrue(UpdateManager.compareVersions("1.9", "1.15") < 0)
        assertEquals(0, UpdateManager.compareVersions("1.12", "1.12"))
        val highest = listOf("1.9", "1.15", "1.10", "1.2")
            .maxWithOrNull(UpdateManager::compareVersions)
        assertEquals("1.15", highest)
    }
}
