package at.mtr.logmygut.reminder

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderTest {
    private val vienna = ZoneId.of("Europe/Vienna")
    private val nine = LocalTime.of(21, 0)

    private fun at(text: String) = LocalDateTime.parse(text).atZone(vienna)

    @Test fun laterTodayFiresToday() = assertEquals(Duration.ofMinutes(30), delayUntilNext(at("2026-10-24T20:30"), nine))

    @Test fun exactlyNowFiresTomorrow() = assertEquals(Duration.ofHours(24), delayUntilNext(at("2026-10-20T21:00"), nine))

    /** Summer time ends on 25 Oct 2026 at 03:00: the night has 25 hours, the wall-clock time stays 21:00. */
    @Test fun keepsWallClockTimeAcrossDaylightSavingChange() =
        assertEquals(Duration.ofHours(24), delayUntilNext(at("2026-10-24T22:00"), nine))
}
