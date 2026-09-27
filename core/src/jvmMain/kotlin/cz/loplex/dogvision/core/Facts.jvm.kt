package cz.loplex.dogvision.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

actual fun formatSignificant(value: Double, digits: Int, decimalSeparator: Char): String =
    BigDecimal(value).round(MathContext(digits, RoundingMode.HALF_EVEN)).stripTrailingZeros().toPlainString()
        .replace('.', decimalSeparator)

actual fun formatFixed(value: Double, decimals: Int, decimalSeparator: Char): String =
    BigDecimal(value).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString().replace('.', decimalSeparator)
