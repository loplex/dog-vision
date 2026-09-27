package cz.loplex.dogvision.core

import kotlin.math.abs

// JavaScript's toExponential and toFixed round the double's exact value, as BigDecimal does, but a tie away from zero.
// A tie is one where the digit past the last kept is a 5 that ends the exact value; when the digit before that 5 is
// even, the number is cut before the 5 instead, which rounds it to the even digit.

actual fun formatSignificant(value: Double, digits: Int, decimalSeparator: Char): String {
    require(digits in 1..MAX_DIGITS) { "Cannot round to $digits significant digits" }
    if (value == 0.0) return "0"
    val finer = value.toExponential(digits)
    val mantissa = finer.substringBefore('e')
    val rounded = if (isTie(mantissa, plain(finer), value)) {
        mantissa.dropLast(1).removeSuffix(".") + "e" + finer.substringAfter('e')
    } else {
        value.toExponential(digits - 1)
    }
    return stripFraction(plain(rounded)).replace('.', decimalSeparator)
}

actual fun formatFixed(value: Double, decimals: Int, decimalSeparator: Char): String {
    require(decimals in 0 until MAX_DIGITS) { "Cannot round to $decimals decimals" }
    require(abs(value) < 1e21) { "toFixed writes $value with an exponent" }
    val finer = value.toFixed(decimals + 1)
    val rounded = if (isTie(finer, finer, value)) finer.dropLast(1).removeSuffix(".") else value.toFixed(decimals)
    // BigDecimal has no negative zero.
    val zero = rounded.all { it == '-' || it == '0' || it == '.' }
    return (if (zero) rounded.removePrefix("-") else rounded).replace('.', decimalSeparator)
}

/** The most digits [isDyadic] reckons with: 5^25, and 10 times it, still fit a Long. */
private const val MAX_DIGITS = 24

private fun Double.toExponential(decimals: Int): String = asDynamic().toExponential(decimals) as String

private fun Double.toFixed(decimals: Int): String = asDynamic().toFixed(decimals) as String

/**
 * Whether [value], written one digit longer than wanted as [digits] (the digits alone) and [decimal] (as a plain
 * number), is a tie that goes to the even digit towards zero.
 */
private fun isTie(digits: String, decimal: String, value: Double): Boolean {
    if (digits.last() != '5') return false
    val before = digits.dropLast(1).last { it.isDigit() }
    return (before - '0') % 2 == 0 && decimal.toDouble() == value && isDyadic(decimal)
}

/**
 * Whether the plain decimal [text] has a finite binary expansion, and so can be a double's exact value, rather than
 * only the decimal a double is nearest to: N / 10^k is one exactly when 5^k divides N.
 */
private fun isDyadic(text: String): Boolean {
    val fraction = text.substringAfter('.', "")
    if (fraction.length > MAX_DIGITS + 1) return false
    var power = 1L
    repeat(fraction.length) { power *= 5 }
    var remainder = 0L
    for (c in text) if (c.isDigit()) remainder = (remainder * 10 + (c - '0')) % power
    return remainder == 0L
}

/** What toExponential writes, written out: "1.25e-1" as "0.125", "1.23e+3" as "1230". */
private fun plain(exponential: String): String {
    val negative = exponential.startsWith('-')
    val mantissa = exponential.removePrefix("-").substringBefore('e')
    val point = exponential.substringAfter('e').toInt() + 1
    val digits = mantissa.replace(".", "")
    val written = when {
        point <= 0 -> "0." + "0".repeat(-point) + digits
        point >= digits.length -> digits + "0".repeat(point - digits.length)
        else -> digits.substring(0, point) + "." + digits.substring(point)
    }
    return if (negative) "-$written" else written
}

/** [text] without the zeros that end its fraction, nor its point if they were all of it. */
private fun stripFraction(text: String): String = if ('.' in text) text.trimEnd('0').removeSuffix(".") else text
