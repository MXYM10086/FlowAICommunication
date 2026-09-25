package com.flowai.communication.system

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/**
 * Turns the engine's free-form time strings ("明天 15:00", "下周三下午2点", "今晚十一点") into an
 * instant.
 *
 * Deliberately tolerant but limited: whatever it cannot read with confidence yields null, and the
 * caller tells the user instead of guessing a wrong appointment time. A silently shifted meeting
 * is worse than a refused one.
 */
object CalendarTimeParser {

    private val AM_PM = "(?:凌晨|早上|上午|中午|下午|晚上|今晚|明晚)"
    private val CN_NUM = "[零一二两三四五六七八九十]{1,3}"

    // "15:00" / "下午3:30" — an explicit clock reading, optionally with an am/pm prefix.
    private val CLOCK = Regex("($AM_PM)?(\\d{1,2})[:：](\\d{2})")
    // "2点" / "十点半" / "下午2点30分"
    private val HOUR_CN = Regex("($AM_PM)?($CN_NUM|\\d{1,2})点(?:(\\d{1,2})分|半)?")

    fun parse(raw: String?, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (raw.isNullOrBlank()) return null
        val text = raw.replace(Regex("\\s+"), "").trim()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val date = parseDate(text, today)
        val time = parseTime(text)
        if (date == null && time == null) return null
        // A lone time ("15:00 开会") anchors to today; a lone date ("明天评审") starts at 9:00 —
        // both choices are shown in the confirmation dialog, so nothing lands silently.
        return ZonedDateTime.of(date ?: today, time ?: LocalTime.of(9, 0), zone).toInstant().toEpochMilli()
    }

    /** Formats an instant the way the confirmation dialog and result message show it. */
    fun format(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone)
            .format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm EEE", Locale.CHINA))

    // ------------------------------------------------------------------ date

    private fun parseDate(text: String, today: LocalDate): LocalDate? {
        if (text.contains("后天")) return today.plusDays(2)
        if (text.contains("明晚") || text.contains("明天") || text.contains("明日")) return today.plusDays(1)
        if (text.contains("今晚") || text.contains("今天") || text.contains("今日")) return today

        weekday(text, today)?.let { return it }

        Regex("(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})[日号]?").find(text)?.let { m ->
            return safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        Regex("(\\d{4})[-/年](\\d{1,2})(?![\\d月])").find(text)?.let { m ->
            // A year and month but no day: first of the month.
            return safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), 1)
        }
        Regex("(\\d{1,2})月(\\d{1,2})[日号]?").find(text)?.let { m ->
            return safeDate(today.year, m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        return null
    }

    /** 周三 / 下周三 / 星期三: the coming occurrence, or the same weekday next week with 下. */
    private fun weekday(text: String, today: LocalDate): LocalDate? {
        val m = Regex("(下周|这周|本周)?(?:周|星期)([一二三四五六日天])").find(text) ?: return null
        val target = when (m.groupValues[2]) {
            "一" -> DayOfWeek.MONDAY
            "二" -> DayOfWeek.TUESDAY
            "三" -> DayOfWeek.WEDNESDAY
            "四" -> DayOfWeek.THURSDAY
            "五" -> DayOfWeek.FRIDAY
            "六" -> DayOfWeek.SATURDAY
            else -> DayOfWeek.SUNDAY
        }
        val delta = ((target.value - today.dayOfWeek.value + 7) % 7).toLong()
        return if (m.groupValues[1] == "下周") today.plusDays(delta + 7) else today.plusDays(delta)
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()

    // ------------------------------------------------------------------ time

    private fun parseTime(text: String): LocalTime? {
        CLOCK.find(text)?.let { m ->
            val hour = m.groupValues[2].toIntOrNull() ?: return@let
            val minute = m.groupValues[3].toIntOrNull() ?: return@let
            return at(hour, minute, m.groupValues[1])
        }
        HOUR_CN.find(text)?.let { m ->
            val hour = m.groupValues[2].let { it.toIntOrNull() ?: chineseToInt(it) } ?: return@let
            // 点半: the 半 lives in the matched span, not in a capture group.
            val minute = m.groupValues[3].toIntOrNull() ?: if (m.value.endsWith("半")) 30 else 0
            return at(hour, minute, m.groupValues[1])
        }
        // 今晚/明晚 without any explicit hour still names a usable slot.
        if (text.contains("今晚") || text.contains("明晚")) return LocalTime.of(20, 0)
        return null
    }

    private fun at(rawHour: Int, minute: Int, amPm: String): LocalTime? {
        if (rawHour > 23 || minute > 59) return null
        var hour = rawHour
        val pm = amPm == "下午" || amPm == "晚上" || amPm == "今晚" || amPm == "明晚"
        if (pm && hour < 12) hour += 12
        // 中午一点 means 13:00, but 中午十二点 must stay noon.
        if (amPm == "中午" && hour < 12) hour += 12
        if (hour > 23) return null
        return LocalTime.of(hour, minute)
    }

    /** 十 → 10, 十一 → 11, 二十 → 20, 二十三 → 23. */
    private fun chineseToInt(s: String): Int? {
        fun digit(c: Char): Int? = when (c) {
            '零' -> 0
            '一' -> 1
            '二', '两' -> 2
            '三' -> 3
            '四' -> 4
            '五' -> 5
            '六' -> 6
            '七' -> 7
            '八' -> 8
            '九' -> 9
            else -> null
        }
        val ten = s.indexOf('十')
        return when {
            ten < 0 && s.length == 1 -> digit(s[0])
            ten == 0 && s.length == 1 -> 10
            ten == 0 && s.length == 2 -> digit(s[1])?.plus(10)
            ten == 1 && s.length == 2 -> digit(s[0])?.times(10)
            ten == 1 && s.length == 3 -> digit(s[0])?.let { t -> digit(s[2])?.let { o -> t * 10 + o } }
            else -> null
        }
    }
}

/**
 * Writes one confirmed event into the system calendar through CalendarContract.
 *
 * Every failure mode the user can actually hit — no permission, no calendar account on the
 * device, a provider that refuses the insert — comes back as [Result.Failed] with a sentence
 * worth showing, never as an exception escaping into the UI thread.
 */
class CalendarWriter(context: Context) {

    private val appContext: Context = context.applicationContext

    sealed class Result {
        data class Success(val eventId: Long) : Result()
        data class Failed(val message: String) : Result()
    }

    /** Listing calendars needs READ_CALENDAR, inserting needs WRITE_CALENDAR: require both. */
    fun hasPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            appContext.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun writeEvent(
        title: String,
        startMillis: Long,
        durationMinutes: Long = DEFAULT_DURATION_MINUTES,
        location: String? = null,
        description: String? = null
    ): Result {
        if (!hasPermission()) return Result.Failed("没有日历权限，写入已取消；可在系统设置里授权后重试")
        return runCatching {
            val calendarId = primaryCalendarId()
                ?: return Result.Failed("设备上没有找到可用的日历账户，请先在系统日历里添加一个账户")
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, startMillis + durationMinutes * 60_000L)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.EVENT_LOCATION, location.orEmpty())
                put(CalendarContract.Events.DESCRIPTION, description.orEmpty())
                put(CalendarContract.Events.ALL_DAY, 0)
                put(CalendarContract.Events.HAS_ALARM, 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = appContext.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return Result.Failed("系统日历拒绝了这次写入，请稍后重试")
            Result.Success(ContentUris.parseId(uri))
        }.getOrElse { e ->
            // A SecurityException here means the runtime grant is missing or partial (the
            // provider checks READ on query and WRITE on insert separately); say so plainly.
            if (e is SecurityException) {
                Result.Failed("日历权限不完整，写入已取消；请到系统设置授予日历读写权限后重试")
            } else {
                Result.Failed("写入日历失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** The device's primary calendar, or the first one available when none is marked primary. */
    private fun primaryCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY)
        appContext.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                return cursor.getLong(0)
            }
        }
        return null
    }

    companion object {
        const val DEFAULT_DURATION_MINUTES = 60L
    }
}
