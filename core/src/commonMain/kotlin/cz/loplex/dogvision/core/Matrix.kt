package cz.loplex.dogvision.core

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** A small dense matrix of doubles, row by row: all the linear algebra the colour model needs. */
class Matrix(val rows: Int, val cols: Int, private val values: DoubleArray = DoubleArray(rows * cols)) {
    init {
        require(values.size == rows * cols) { "$rows x $cols needs ${rows * cols} values, not ${values.size}" }
    }

    operator fun get(row: Int, col: Int): Double = values[row * cols + col]

    operator fun set(row: Int, col: Int, value: Double) {
        values[row * cols + col] = value
    }

    fun row(row: Int): DoubleArray = DoubleArray(cols) { this[row, it] }

    fun column(col: Int): DoubleArray = DoubleArray(rows) { this[it, col] }

    operator fun times(other: Matrix): Matrix {
        require(cols == other.rows) { "Cannot multiply $rows x $cols by ${other.rows} x ${other.cols}" }
        val product = Matrix(rows, other.cols)
        for (i in 0 until rows) {
            for (j in 0 until other.cols) {
                var sum = 0.0
                for (k in 0 until cols) sum += this[i, k] * other[k, j]
                product[i, j] = sum
            }
        }
        return product
    }

    operator fun times(vector: DoubleArray): DoubleArray {
        require(cols == vector.size) { "Cannot multiply $rows x $cols by a vector of ${vector.size}" }
        return DoubleArray(rows) { i -> (0 until cols).sumOf { k -> this[i, k] * vector[k] } }
    }

    operator fun times(scale: Double): Matrix = map { it * scale }

    operator fun plus(other: Matrix): Matrix = zip(other) { a, b -> a + b }

    operator fun minus(other: Matrix): Matrix = zip(other) { a, b -> a - b }

    fun transpose(): Matrix = build(cols, rows) { i, j -> this[j, i] }

    fun map(transform: (Double) -> Double): Matrix =
        Matrix(rows, cols, DoubleArray(values.size) { transform(values[it]) })

    private fun zip(other: Matrix, combine: (Double, Double) -> Double): Matrix {
        require(rows == other.rows && cols == other.cols) {
            "Cannot combine $rows x $cols with ${other.rows} x ${other.cols}"
        }
        return Matrix(rows, cols, DoubleArray(values.size) { combine(values[it], other.values[it]) })
    }

    /** X such that this X = b, by Gaussian elimination with partial pivoting; this must be square and regular. */
    fun solve(b: Matrix): Matrix {
        require(rows == cols && b.rows == rows) { "Cannot solve $rows x $cols for ${b.rows} x ${b.cols}" }
        val n = rows
        val a = Array(n) { row(it) }
        val x = Array(n) { b.row(it) }
        for (pivot in 0 until n) {
            val best = (pivot until n).maxBy { abs(a[it][pivot]) }
            check(a[best][pivot] != 0.0) { "The matrix is singular" }
            a[pivot] = a[best].also { a[best] = a[pivot] }
            x[pivot] = x[best].also { x[best] = x[pivot] }
            for (r in pivot + 1 until n) {
                val factor = a[r][pivot] / a[pivot][pivot]
                for (c in pivot until n) a[r][c] -= factor * a[pivot][c]
                for (c in 0 until b.cols) x[r][c] -= factor * x[pivot][c]
            }
        }
        for (r in n - 1 downTo 0) {
            for (c in 0 until b.cols) {
                var sum = x[r][c]
                for (k in r + 1 until n) sum -= a[r][k] * x[k][c]
                x[r][c] = sum / a[r][r]
            }
        }
        return build(n, b.cols) { i, j -> x[i][j] }
    }

    fun inverse(): Matrix = solve(identity(rows))

    /** The number of linearly independent rows, pivots below tolerance counting as zero. */
    fun rank(tolerance: Double): Int {
        val a = Array(rows) { row(it) }
        var rank = 0
        for (col in 0 until cols) {
            if (rank == rows) break
            val best = (rank until rows).maxBy { abs(a[it][col]) }
            if (abs(a[best][col]) <= tolerance) continue
            a[rank] = a[best].also { a[best] = a[rank] }
            for (r in rank + 1 until rows) {
                val factor = a[r][col] / a[rank][col]
                for (c in col until cols) a[r][c] -= factor * a[rank][c]
            }
            rank++
        }
        return rank
    }

    /**
     * Eigenvalues and eigenvectors (as columns) of this symmetric matrix, by Jacobi rotations,
     * the way numpy.linalg.eigh gives them but in no particular order.
     */
    fun symmetricEigen(): Pair<DoubleArray, Matrix> {
        require(rows == cols) { "Only a square matrix has eigenvalues" }
        val n = rows
        val a = Array(n) { row(it) }
        val v = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
        repeat(100) {
            var offDiagonal = 0.0
            for (p in 0 until n) for (q in p + 1 until n) offDiagonal += a[p][q] * a[p][q]
            if (offDiagonal <= 1e-30 * (0 until n).sumOf { a[it][it] * a[it][it] }.coerceAtLeast(1e-300)) {
                return DoubleArray(n) { a[it][it] } to build(n, n) { i, j -> v[i][j] }
            }
            for (p in 0 until n) {
                for (q in p + 1 until n) {
                    if (a[p][q] == 0.0) continue
                    val theta = (a[q][q] - a[p][p]) / (2 * a[p][q])
                    val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                    val c = 1 / sqrt(t * t + 1)
                    val s = t * c
                    for (k in 0 until n) {
                        val akp = a[k][p]
                        val akq = a[k][q]
                        a[k][p] = c * akp - s * akq
                        a[k][q] = s * akp + c * akq
                    }
                    for (k in 0 until n) {
                        val apk = a[p][k]
                        val aqk = a[q][k]
                        a[p][k] = c * apk - s * aqk
                        a[q][k] = s * apk + c * aqk
                    }
                    for (k in 0 until n) {
                        val vkp = v[k][p]
                        val vkq = v[k][q]
                        v[k][p] = c * vkp - s * vkq
                        v[k][q] = s * vkp + c * vkq
                    }
                }
            }
        }
        error("Jacobi rotations did not converge")
    }

    /** This symmetric positive definite matrix raised to a power, through its eigendecomposition. */
    fun symmetricPower(power: Double): Matrix {
        val (eigenvalues, vectors) = symmetricEigen()
        val scaled = build(rows, cols) { i, j -> vectors[i, j] * eigenvalues[j].pow(power) }
        return scaled * vectors.transpose()
    }

    override fun toString(): String =
        (0 until rows).joinToString("\n") { r -> row(r).joinToString(" ") { formatSignificant(it, 6, '.') } }

    companion object {
        fun build(rows: Int, cols: Int, value: (Int, Int) -> Double): Matrix {
            val matrix = Matrix(rows, cols)
            for (i in 0 until rows) for (j in 0 until cols) matrix[i, j] = value(i, j)
            return matrix
        }

        fun of(vararg rows: DoubleArray): Matrix = build(rows.size, rows.first().size) { i, j -> rows[i][j] }

        fun identity(n: Int): Matrix = build(n, n) { i, j -> if (i == j) 1.0 else 0.0 }

        fun diagonal(values: DoubleArray): Matrix =
            build(values.size, values.size) { i, j -> if (i == j) values[i] else 0.0 }

        fun ones(rows: Int, cols: Int): Matrix = build(rows, cols) { _, _ -> 1.0 }

        /** The outer product a b'. */
        fun outer(a: DoubleArray, b: DoubleArray): Matrix = build(a.size, b.size) { i, j -> a[i] * b[j] }
    }
}
