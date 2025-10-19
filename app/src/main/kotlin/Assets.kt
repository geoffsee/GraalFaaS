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
        val id: String? = null,
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

        val assignedId = generateUuidV7()

        return FunctionAsset(
            id = assignedId,
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

    /**
     * Generate a UUIDv7 string per RFC 4122 (time-ordered, millisecond precision).
     * We use 48 bits of current epoch milliseconds, a 12-bit random field,
     * and a 62-bit random field with the RFC variant bits set.
     */
    fun generateUuidV7(nowMillis: Long = System.currentTimeMillis()): String {
        val ms = nowMillis and 0x0000_FFFF_FFFF_FFFFL // 48 bits
        val randA = SECURE_RANDOM.nextInt(1 shl 12) // 12 bits
        val randB = SECURE_RANDOM.nextLong() and ((1L shl 62) - 1) // 62 bits

        var msb = (ms shl 16)
        msb = msb or (0x7L shl 12) // version 7
        msb = msb or randA.toLong()

        val lsb = (0x2L shl 62) or randB // variant 10
        return java.util.UUID(msb, lsb).toString()
    }

    private val SECURE_RANDOM: java.security.SecureRandom = java.security.SecureRandom()

    private fun readFile(path: Path): String = Files.readString(path)
}