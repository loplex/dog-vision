package cz.loplex.dogvision.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Writes the POSIX sh script a deb or an rpm starts [mainClass] with,
 * on the system's Java rather than one of the package's own: it finds a Java [minimumJava] or newer,
 * and one with AWT's X11 library where the program [opensWindow], as launcher.sh, beside this class, says.
 */
abstract class JavaLauncher : DefaultTask() {
    /** The command's name, which it is installed as and says in its error. */
    @get:Input
    abstract val commandName: Property<String>

    @get:Input
    abstract val mainClass: Property<String>

    /** The JARs, in the order the classpath takes them; those that hold natives only ([nativesOnly]) are left out. */
    @get:Classpath
    abstract val jars: ConfigurableFileCollection

    /** Where the package installs [jars]. */
    @get:Input
    abstract val jarDirectory: Property<String>

    /** Those of [jars] that another package installs, in [sharedJarDirectory], rather than this one. */
    @get:Classpath
    abstract val sharedJars: ConfigurableFileCollection

    /** Where the other package installs [sharedJars]. */
    @get:Input
    @get:Optional
    abstract val sharedJarDirectory: Property<String>

    /** The names [jars] are installed under where they are not their own, keyed by each JAR's path. */
    @get:Input
    abstract val jarNames: MapProperty<String, String>

    @get:Input
    abstract val jvmOptions: ListProperty<String>

    @get:Input
    abstract val minimumJava: Property<Int>

    /** Whether the program opens a window, so that a headless Java will not do; false by default. */
    @get:Input
    abstract val opensWindow: Property<Boolean>

    @get:OutputFile
    abstract val script: RegularFileProperty

    init {
        jarNames.convention(emptyMap())
        opensWindow.convention(false)
    }

    @TaskAction
    fun write() {
        val names = jarNames.get()
        val shared = sharedJars.files
        // A JAR of a shared one's name and not the same file would be the shared one, of another version or build.
        val unlike = jars.files.filter { jar -> jar !in shared && shared.any { it.name == jar.name } }
        check(unlike.isEmpty()) { "$unlike differ from the shared JARs of their names in $shared" }
        val classpath = jars.files.filterNot(::nativesOnly).map { jar ->
            when (jar) {
                in shared -> "${sharedJarDirectory.get()}/${jar.name}"
                else -> "${jarDirectory.get()}/${names[jar.path] ?: jar.name}"
            }
        }
        check(classpath.distinct().size == classpath.size) { "Two JARs of one name in $classpath" }
        val unquotable = (classpath + jvmOptions.get()).filter { '\'' in it }
        check(unquotable.isEmpty()) { "The launcher cannot put $unquotable in single quotes" }
        val window = opensWindow.get()
        val template = checkNotNull(javaClass.getResource("launcher.sh")) { "No launcher.sh beside JavaLauncher" }
        val text = template.readText()
            .replace("@NAME@", commandName.get())
            .replace("@JAVA@", "Java ${minimumJava.get()} or newer" + if (window) ", not a headless one" else "")
            .replace("@MINIMUM@", minimumJava.get().toString())
            .replace("@WINDOW@", if (window) "yes" else "no")
            .replace("@JVM_OPTIONS@", jvmOptions.get().joinToString("") { "'$it' " })
            .replace("@CLASSPATH@", classpath.joinToString(":"))
            .replace("@MAIN_CLASS@", mainClass.get())
        val file = script.get().asFile
        file.writeText(text)
        file.setExecutable(true)
    }
}
