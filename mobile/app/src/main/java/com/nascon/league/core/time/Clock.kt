package com.nascon.league.core.time

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object Clock {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun format(instant: Instant): String = formatter.format(instant)

    fun nowUtc(): String = format(Instant.now())

    fun nowEpochSeconds(): Long = Instant.now().epochSecond
}
