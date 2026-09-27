package cz.loplex.dogvision.core

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.fail

/** Fails unless every value is within atol + rtol * |expected|, as numpy.testing.assert_allclose. */
fun assertAllClose(
    expected: DoubleArray,
    actual: DoubleArray,
    rtol: Double = 1e-7,
    atol: Double = 0.0,
    what: String = "",
) {
    assertEquals(expected.size, actual.size, "$what: sizes differ")
    for (i in expected.indices) {
        if (abs(actual[i] - expected[i]) > atol + rtol * abs(expected[i])) {
            fail(
                "$what: element $i is ${actual[i]}, expected ${expected[i]}" +
                    "\nexpected ${expected.toList()}\nactual   ${actual.toList()}",
            )
        }
    }
}

fun assertAllClose(expected: Matrix, actual: Matrix, rtol: Double = 1e-7, atol: Double = 0.0, what: String = "") {
    assertEquals(expected.rows to expected.cols, actual.rows to actual.cols, "$what: shapes differ")
    assertAllClose(expected.flatten(), actual.flatten(), rtol, atol, what)
}

fun assertAllClose(expected: Double, actual: Matrix, rtol: Double = 1e-7, atol: Double = 0.0, what: String = "") =
    assertAllClose(DoubleArray(actual.rows * actual.cols) { expected }, actual.flatten(), rtol, atol, what)

fun Matrix.flatten(): DoubleArray = DoubleArray(rows * cols) { this[it / cols, it % cols] }
