package dev.skyloft.skypython.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private val hour = 60 * 60 * 1000L
    private val now = 1_000_000_000_000L

    @Test
    fun autoCheckAtMostEvery12Hours() {
        assertTrue(UpdatePolicy.shouldAutoCheck(enabled = true, lastCheckAt = null, now = now))
        assertFalse(UpdatePolicy.shouldAutoCheck(enabled = true, lastCheckAt = now - 11 * hour, now = now))
        assertFalse(UpdatePolicy.shouldAutoCheck(enabled = true, lastCheckAt = now - 12 * hour + 1, now = now))
        assertTrue(UpdatePolicy.shouldAutoCheck(enabled = true, lastCheckAt = now - 12 * hour, now = now))
        assertTrue(UpdatePolicy.shouldAutoCheck(enabled = true, lastCheckAt = now - 100 * hour, now = now))
    }

    @Test
    fun clockSetBackDoesNotStopChecksForever() {
        // The last check is "in the future" (clock changed): check again.
        assertTrue(UpdatePolicy.shouldAutoCheck(enabled = true, lastCheckAt = now + 5 * hour, now = now))
    }

    @Test
    fun turnedOffMeansNoAutoCheck() {
        assertFalse(UpdatePolicy.shouldAutoCheck(enabled = false, lastCheckAt = null, now = now))
    }

    @Test
    fun skippedVersionIsNotAnnouncedButANewerOneIs() {
        val v6 = SemVer(0, 0, 6)
        assertFalse(UpdatePolicy.shouldAnnounce(v6, skipped = "0.0.6"))
        assertTrue(UpdatePolicy.shouldAnnounce(SemVer(0, 0, 7), skipped = "0.0.6"))
        assertTrue(UpdatePolicy.shouldAnnounce(v6, skipped = null))
        assertTrue(UpdatePolicy.shouldAnnounce(v6, skipped = "garbage"))
    }

    @Test
    fun bannerHiddenForTheDismissedVersionOnly() {
        val v6 = SemVer(0, 0, 6)
        assertFalse(UpdatePolicy.showBanner(v6, dismissed = "0.0.6"))
        assertTrue(UpdatePolicy.showBanner(SemVer(0, 0, 7), dismissed = "0.0.6"))
        assertTrue(UpdatePolicy.showBanner(v6, dismissed = null))
    }
}
