package ltd.gsio.app

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Models and utilities for storing and loading function assets.
 */
object Assets {
    data class FunctionAsset(
        val id: String,
        val languageId: String,
        val functionName: String = "handler",
        val jsEvalAsModule: Boolean = false,
        val sourceCode: String,
        val dependencies: Map<String, String> = emptyMap()
    )

    /** Manifest format for CLI upload (JSON or JSONC).
     *  - source or sourceFile is required (one of them)
     *  - dependencies values can be inline ("source") or file-backed ("file")
     */
    data class UploadManifest(
        val id: String,
        val languageId: String,
        val functionName: String? = null,
        val jsEvalAsModule: Boolean? = null,
        val source: String? = null,
        val sourceFile: String? = null,
        val dependencies: Map<String, DependencySpec>? = null
    )

    data class DependencySpec(
        val source: String? = null,
        val file: String? = null
    )

    val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    private fun baseDir(): Path = Path.of(".faas", "functions")

    fun ensureDirs() {
        Files.createDirectories(baseDir())
    }

    fun assetPath(id: String): Path = baseDir().resolve("$id.json")

    fun save(asset: FunctionAsset) {
        ensureDirs()
        val json = gson.toJson(asset)
        Files.writeString(assetPath(asset.id), json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    }

    fun load(id: String): FunctionAsset? {
        val p = assetPath(id)
        return if (Files.exists(p)) {
            Files.newBufferedReader(p).use { reader ->
                gson.fromJson(reader, FunctionAsset::class.java)
            }
        } else null
    }

    fun list(): List<FunctionAsset> {
        val dir = baseDir().toFile()
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f: File -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                try {
                    f.bufferedReader().use { reader ->
                        gson.fromJson(reader, FunctionAsset::class.java)
                    }
                } catch (_: Exception) { null }
            }
            ?: emptyList()
    }

    /** Convert an UploadManifest (JSON/JSONC) into a stored FunctionAsset, resolving files.
     *  Paths are resolved relative to [cwd].
     */
    fun toAsset(cwd: Path, manifest: UploadManifest): FunctionAsset {
        val src = manifest.source ?: manifest.sourceFile?.let { readFile(cwd.resolve(it).normalize()) }
        require(!src.isNullOrBlank()) { "Manifest must include 'source' or 'sourceFile'" }

        val deps: Map<String, String> = manifest.dependencies?.mapValues { (_, spec) ->
            spec.source ?: spec.file?.let { readFile(cwd.resolve(it).normalize()) }
            ?: error("Dependency requires either 'source' or 'file'")
        } ?: emptyMap()

        return FunctionAsset(
            id = manifest.id,
            languageId = manifest.languageId,
            functionName = manifest.functionName ?: "handler",
            jsEvalAsModule = manifest.jsEvalAsModule ?: false,
            sourceCode = src,
            dependencies = deps
        )
    }

    fun readManifest(file: Path): UploadManifest {
        return Files.newBufferedReader(file).use { reader ->
            gson.fromJson(reader, UploadManifest::class.java)
        }
    }

    private fun readFile(path: Path): String = Files.readString(path)
}