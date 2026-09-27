package cz.loplex.dogvision.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The numbers of the facts, written alike on every platform: rounded from the double's exact value, a tie to the even
 * digit, as Python rounds them, but written out without an exponent and without a negative zero.
 */
class FormatTest {
    @Test
    fun significantDigits() {
        val cases = listOf(
            Triple(420.7, 6, "420.7"),
            Triple(429.0, 6, "429"),
            Triple(3.66, 3, "3.66"),
            Triple(0.1, 6, "0.1"),
            Triple(1.0, 6, "1"),
            Triple(100.0, 3, "100"),
            Triple(0.000123456, 3, "0.000123"),
            Triple(1e-7, 2, "0.0000001"),
            Triple(1234.5, 3, "1230"),
            Triple(12345678.0, 4, "12350000"),
            // 9.95 is a little less than that, so no tie.
            Triple(9.95, 2, "9.9"),
            // Ties.
            Triple(0.125, 2, "0.12"),
            Triple(0.375, 2, "0.38"),
            Triple(2.5, 1, "2"),
            Triple(3.5, 1, "4"),
            Triple(9.5, 1, "10"),
            Triple(15.0, 1, "20"),
            Triple(25.0, 1, "20"),
            Triple(-0.125, 2, "-0.12"),
            Triple(-2.5, 1, "-2"),
            Triple(0.0, 3, "0"),
            Triple(-0.0, 3, "0"),
        )
        for ((value, digits, expected) in cases) {
            assertEquals(expected, formatSignificant(value, digits, '.'), "$value to $digits significant digits")
        }
    }

    @Test
    fun fixedDecimals() {
        val cases = listOf(
            Triple(0.79123, 2, "0.79"),
            Triple(480.49, 0, "480"),
            Triple(1e20, 0, "100000000000000000000"),
            Triple(0.0, 2, "0.00"),
            // 1.005 is a little less than that, and -1.35 a little more negative, so no ties.
            Triple(1.005, 2, "1.00"),
            Triple(-1.35, 1, "-1.4"),
            // Ties.
            Triple(0.125, 2, "0.12"),
            Triple(0.375, 2, "0.38"),
            Triple(0.5, 0, "0"),
            Triple(1.5, 0, "2"),
            Triple(2.5, 0, "2"),
            Triple(12.5, 0, "12"),
            Triple(13.5, 0, "14"),
            Triple(-1.25, 1, "-1.2"),
            Triple(-0.5, 0, "0"),
            Triple(-0.4, 0, "0"),
            Triple(-0.0, 2, "0.00"),
        )
        for ((value, decimals, expected) in cases) {
            assertEquals(expected, formatFixed(value, decimals, '.'), "$value to $decimals decimals")
        }
    }

    @Test
    fun decimalSeparator() {
        assertEquals("420,7", formatSignificant(420.7, 6, ','))
        assertEquals("0,79", formatFixed(0.79123, 2, ','))
    }
}
