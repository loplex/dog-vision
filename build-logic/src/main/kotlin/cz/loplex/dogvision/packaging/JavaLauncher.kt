package cz.loplex.dogvision.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Writes the POSIX sh script a deb or an rpm starts [mainClass] with, on the system's Java rather than one of the
 * package's own: it finds a Java [minimumJava] or newer as launcher.sh, beside this class, says.
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

    @get:Input
    abstract val jvmOptions: ListProperty<String>

    @get:Input
    abstract val minimumJava: Property<Int>

    @get:OutputFile
    abstract val script: RegularFileProperty

    @TaskAction
    fun write() {
        val classpath = jars.files.filterNot(::nativesOnly).map { "${jarDirectory.get()}/${it.name}" }
        check(classpath.distinct().size == classpath.size) { "Two JARs of one name in $classpath" }
        val unquotable = (classpath + jvmOptions.get()).filter { '\'' in it }
        check(unquotable.isEmpty()) { "The launcher cannot put $unquotable in single quotes" }
        val template = checkNotNull(javaClass.getResource("launcher.sh")) { "No launcher.sh beside JavaLauncher" }
        val text = template.readText()
            .replace("@NAME@", commandName.get())
            .replace("@MINIMUM@", minimumJava.get().toString())
            .replace("@JVM_OPTIONS@", jvmOptions.get().joinToString("") { "'$it' " })
            .replace("@CLASSPATH@", classpath.joinToString(":"))
            .replace("@MAIN_CLASS@", mainClass.get())
        val file = script.get().asFile
        file.writeText(text)
        file.setExecutable(true)
    }
}
