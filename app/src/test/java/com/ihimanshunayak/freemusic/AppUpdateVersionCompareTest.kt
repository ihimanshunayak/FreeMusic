package com.ihimanshunayak.freemusic

import com.ihimanshunayak.freemusic.data.AppUpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the version comparison the in-app updater depends on.
 *
 * The version scheme is date-based (`DD.MM.YY[.N]` — see
 * `docs/VERSION_DATE_SYSTEM.md`), so it has to be compared as a calendar
 * date. Comparing it as plain dotted numbers is wrong the moment the day
 * rolls over: `08.11.26` is 8 November, which is *newer* than `28.10.26`
 * (28 October), but 8 < 28 makes the numeric reading call it older. A user
 * on the October build would then never be told about the November release
 * — which is exactly what the updater did before this was fixed.
 */
class AppUpdateVersionCompareTest {

    @Test
    fun `a later day in the same month is newer`() {
        assertTrue(AppUpdateChecker.isNewer("08.11.26", "07.11.26"))
    }

    @Test
    fun `a later day in the same month is newer even when the day number drops`() {
        // The case that broke the updater: 8 November must beat 28 October
        // even though 8 is numerically smaller than 28.
        assertTrue(AppUpdateChecker.isNewer("08.11.26", "28.10.26"))
    }

    @Test
    fun `a later month in the same year is newer`() {
        assertTrue(AppUpdateChecker.isNewer("01.12.26", "28.11.26"))
    }

    @Test
    fun `a later year is newer`() {
        assertTrue(AppUpdateChecker.isNewer("01.01.27", "28.12.26"))
    }

    @Test
    fun `the same day with a higher counter is newer`() {
        assertTrue(AppUpdateChecker.isNewer("28.10.26.2", "28.10.26.1"))
    }

    @Test
    fun `the first build of a day outranks the previous day's last build`() {
        // The counter resets when the day rolls over, so "no counter" means
        // counter 1 — and it must still beat yesterday's counter 19.
        assertTrue(AppUpdateChecker.isNewer("29.10.26", "28.10.26.19"))
    }

    @Test
    fun `an older release is not newer`() {
        assertFalse(AppUpdateChecker.isNewer("07.11.26", "08.11.26"))
    }

    @Test
    fun `the same version is not newer`() {
        assertFalse(AppUpdateChecker.isNewer("08.11.26", "08.11.26"))
    }

    @Test
    fun `the same day with a lower counter is not newer`() {
        assertFalse(AppUpdateChecker.isNewer("28.10.26.1", "28.10.26.19"))
    }

    @Test
    fun `a date-based release is newer than a legacy numbered build`() {
        // Anything still on "1.20" predates every date-based build, so it
        // must be offered the update rather than told there is nothing new.
        assertTrue(AppUpdateChecker.isNewer("08.11.26", "1.20"))
    }

    @Test
    fun `a legacy numbered release is never newer than a date-based build`() {
        assertFalse(AppUpdateChecker.isNewer("1.20", "08.11.26"))
    }

    @Test
    fun `legacy versions compare numerically`() {
        // "1.10" really does outrank "1.9" in the legacy scheme.
        assertTrue(AppUpdateChecker.isNewer("1.10", "1.9"))
        assertFalse(AppUpdateChecker.isNewer("1.9", "1.10"))
        assertFalse(AppUpdateChecker.isNewer("1.9", "1.9"))
    }

    @Test
    fun `a plain release outranks its own pre-release`() {
        assertTrue(AppUpdateChecker.isNewer("08.11.26", "08.11.26-beta2"))
    }

    @Test
    fun `a pre-release is not newer than the release it tests toward`() {
        assertFalse(AppUpdateChecker.isNewer("08.11.26-beta2", "08.11.26"))
    }

    @Test
    fun `a pre-release of a later date is still newer than an earlier release`() {
        assertTrue(AppUpdateChecker.isNewer("09.11.26-beta2", "08.11.26"))
    }

    @Test
    fun `a version that is not a plausible date stays on the numeric path`() {
        // 99.99.99 is not a calendar date, so it falls back to the plain
        // numeric comparison rather than being read as day 99 of month 99.
        assertTrue(AppUpdateChecker.isNewer("99.99.99", "98.98.98"))
    }
}
