package cz.loplex.dogvision.packaging.jvm

// What LWJGL needs of Java, granted to the classpath, where a window's JARs are:
// - Native access, as LWJGL loads its natives with System.load, which Java 25 warns about without it.
// - The package jdk.internal.misc, whose Unsafe LWJGL takes where it may.
//   Without it, LWJGL falls back to sun.misc.Unsafe, whose memory access Java 25 warns about too.
private const val NATIVE_ACCESS = "ALL-UNNAMED"
private val EXPORTS = listOf("java.base/jdk.internal.misc")

/** What LWJGL needs as an uber JAR's manifest attributes, which Java takes from the JAR `java -jar` runs alone. */
val LWJGL_MANIFEST_ATTRIBUTES: Map<String, String> =
    mapOf("Enable-Native-Access" to NATIVE_ACCESS, "Add-Exports" to EXPORTS.joinToString(" "))

/** What LWJGL needs as Java's options, for a launcher that puts the JARs on the classpath. */
val LWJGL_JAVA_OPTIONS: List<String> =
    listOf("--enable-native-access=$NATIVE_ACCESS") + EXPORTS.map { "--add-exports=$it=ALL-UNNAMED" }
