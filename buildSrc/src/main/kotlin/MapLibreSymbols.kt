import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import javax.inject.Inject

/** MapLibre's AAR libraries unstripped, from its GitHub release, so release bundles carry their symbols. */
abstract class MapLibreSymbols : DefaultTask() {
    @get:Input abstract val version: Property<String>
    @get:Input abstract val abis: SetProperty<String>
    @get:Internal abstract val ndkDirectory: DirectoryProperty
    @get:Internal abstract val cacheDirectory: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Inject abstract val archives: ArchiveOperations
    @get:Inject abstract val files: FileSystemOperations

    init {
        outputs.upToDateWhen { ndkDirectory.get().asFile.isDirectory }
    }

    @TaskAction
    fun extract() {
        check(ndkDirectory.get().asFile.isDirectory) {
            "Install ndk/${ndkDirectory.get().asFile.name}: without it AGP packages libmaplibre.so unstripped"
        }
        val name = "debug-symbols-maplibre-android-opengl-release-android-v${version.get()}.tar.gz"
        val archive = cacheDirectory.file(name).get().asFile
        if (!archive.exists()) {
            val partial = File(archive.path + ".part")
            partial.parentFile.mkdirs()
            URI("https://github.com/maplibre/maplibre-native/releases/download/android-v${version.get()}/$name").toURL()
                .openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            check(partial.renameTo(archive)) { "Could not move $partial to $archive" }
        }
        files.sync {
            from(archives.tarTree(archives.gzip(archive)))
            include(abis.get().map { "$it/libmaplibre.so" })
            into(outputDirectory)
        }
    }
}
