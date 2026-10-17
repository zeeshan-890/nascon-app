package com.nascon.league.data.store

/** Formats minor units as "PKR 4,500.00". Locale independent. */
fun formatPkr(minor: Long): String {
    val negative = minor < 0
    // Work on the digits of the string so Long.MIN_VALUE cannot overflow.
    val digits = minor.toString().removePrefix("-")
    val padded = digits.padStart(3, '0')
    val whole = padded.dropLast(2)
    val fraction = padded.takeLast(2)
    val grouped = StringBuilder()
    whole.forEachIndexed { i, c ->
        if (i > 0 && (whole.length - i) % 3 == 0) grouped.append(',')
        grouped.append(c)
    }
    return (if (negative) "-" else "") + "PKR $grouped.$fraction"
}
