package com.nascon.league.matchlive

import com.nascon.league.data.realtime.LiveMessage
import java.math.BigInteger
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Pure logic for the Part II match screens. No Android types. */
object MatchLiveLogic {

    fun formatScore(home: Int?, away: Int?): String = "${home ?: 0} - ${away ?: 0}"

    fun formatPrice(minor: Long): String {
        val abs = BigInteger.valueOf(minor).abs()
        val parts = abs.divideAndRemainder(BigInteger.valueOf(100))
        val whole = parts[0].toString()
        val frac = parts[1].toInt().toString().padStart(2, '0')
        val sb = StringBuilder()
        for ((i, c) in whole.withIndex()) {
            if (i > 0 && (whole.length - i) % 3 == 0) sb.append(',')
            sb.append(c)
        }
        val sign = if (minor < 0) "-" else ""
        return "${sign}PKR $sb.$frac"
    }

    enum class StatusKind { LIVE, UPCOMING, COMPLETED, POSTPONED, CANCELLED, ABANDONED, UNKNOWN }

    data class StatusInfo(val kind: StatusKind, val text: String)

    fun statusInfo(status: String?): StatusInfo =
        when (status?.trim()?.lowercase()) {
            "in_progress" -> StatusInfo(StatusKind.LIVE, "LIVE")
            "upcoming" -> StatusInfo(StatusKind.UPCOMING, "Upcoming")
            "completed" -> StatusInfo(StatusKind.COMPLETED, "Completed")
            "postponed" -> StatusInfo(StatusKind.POSTPONED, "Postponed")
            "cancelled" -> StatusInfo(StatusKind.CANCELLED, "Cancelled")
            "abandoned" -> StatusInfo(StatusKind.ABANDONED, "Abandoned")
            else -> StatusInfo(StatusKind.UNKNOWN, "Status unknown")
        }

    data class Applied(val snapshot: MatchSnapshot, val needsRefetch: Boolean)

    private fun JsonObject.str(key: String): String? {
        val p = this[key] as? JsonPrimitive ?: return null
        return if (p.isString) p.content else null
    }

    private fun JsonObject.num(key: String): Long? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (p.isString) return null
        return p.longOrNull ?: p.doubleOrNull?.takeIf { it.isFinite() }?.toLong()
    }

    private fun JsonObject.score(side: String): Int? {
        val o = this[side] as? JsonObject ?: return null
        return o.num("score")?.toInt()
    }

    fun applyEvent(cur: MatchSnapshot, msg: LiveMessage): Applied {
        val data = msg.data
        return when (msg.type) {
            "match.status_changed" -> {
                val s = data?.str("status")
                if (s == null) Applied(cur, false) else Applied(cur.copy(status = s), false)
            }
            "match.snapshot" -> {
                if (data == null) return Applied(cur, false)
                val v = data.num("version")
                if (v != null && v < cur.version) return Applied(cur, false)
                Applied(
                    cur.copy(
                        status = data.str("status") ?: cur.status,
                        homeScore = data.score("home") ?: cur.homeScore,
                        awayScore = data.score("away") ?: cur.awayScore,
                        version = v ?: cur.version,
                    ),
                    false,
                )
            }
            "match.event_appended" -> {
                val v = data?.num("version")
                val next = if (v != null && v > cur.version) cur.copy(version = v) else cur
                Applied(next, true)
            }
            "product.pinned" -> Applied(cur, true)
            else -> Applied(cur, false)
        }
    }

    fun countdown(nowMillis: Long, kickoffMillis: Long): Countdown {
        val diffMs = kickoffMillis - nowMillis
        if (diffMs <= 0) return Countdown(0, 0, 0, 0, true)
        val total = diffMs / 1000
        return Countdown(
            days = total / 86_400,
            hours = ((total % 86_400) / 3_600).toInt(),
            minutes = ((total % 3_600) / 60).toInt(),
            seconds = (total % 60).toInt(),
            finished = false,
        )
    }

    fun parseIsoMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(iso.trim()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    fun chatRoomIdFor(matchId: String): String = "chat-$matchId"

    fun minuteLabel(snapshot: MatchSnapshot): String? =
        snapshot.minuteLabel?.trim()?.takeIf { it.isNotEmpty() }
}
