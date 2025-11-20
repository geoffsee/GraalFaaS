package ltd.gsio.app

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Host-managed runtime resources (context-independent). Resources are created via HTTP and
 * assigned ownership to one or more functions. During invocation of a function, a lightweight
 * Platform handle exposing the owned resources is injected into the event as `event.platform`.
 *
 * Supported resource types (initial):
 * - kv: simple in-memory key-value store per resource id (demo-grade; process lifetime)
 * - sql: stub definition (not yet implemented)
 */
object Resources {
    // ---------- Persistence models ----------
    data class ResourceRecord(
        val id: String,
        val type: String, // "kv" | "sql" | ...
        val owners: Set<String> = emptySet(), // function IDs that can access this resource
        val config: Map<String, String> = emptyMap() // type-specific config
    )

    data class CreateRequest(
        val type: String,
        val owners: Set<String>? = null,
        val config: Map<String, String>? = null
    )

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    // In-memory indices to avoid disk round-trips during a single process lifetime
    private val ownerIndex: ConcurrentHashMap<String, MutableSet<String>> = ConcurrentHashMap() // functionId -> resourceIds

    private fun baseDir(): Path = Path.of(".faas", "resources")

    fun ensureDirs() {
        Files.createDirectories(baseDir())
    }

    private fun resourcePath(id: String): Path = baseDir().resolve("$id.json")

    fun list(): List<ResourceRecord> {
        val dir = baseDir().toFile()
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                try { f.bufferedReader().use { gson.fromJson(it, ResourceRecord::class.java) } } catch (_: Exception) { null }
            } ?: emptyList()
    }

    fun load(id: String): ResourceRecord? {
        val p = resourcePath(id)
        if (!Files.exists(p)) return null
        return Files.newBufferedReader(p).use { gson.fromJson(it, ResourceRecord::class.java) }
    }

    fun save(record: ResourceRecord) {
        ensureDirs()
        val json = gson.toJson(record)
        Files.writeString(resourcePath(record.id), json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    }

    fun create(req: CreateRequest): ResourceRecord {
        ensureDirs()
        val id = Assets.generateUuidV7()
        val record = ResourceRecord(
            id = id,
            type = req.type.lowercase(),
            owners = req.owners ?: emptySet(),
            config = req.config ?: emptyMap()
        )
        save(record)
        index(record)
        // provision runtime handle if necessary
        when (record.type) {
            "kv" -> kvStores.computeIfAbsent(record.id) { ConcurrentHashMap() }
        }
        return record
    }

    fun attachOwner(resourceId: String, functionId: String): ResourceRecord {
        val existing = load(resourceId) ?: error("Resource not found: $resourceId")
        val updated = existing.copy(owners = existing.owners + functionId)
        save(updated)
        index(updated)
        return updated
    }

    // ---------- Runtime handles (process-lifetime) ----------
    private val kvStores: ConcurrentHashMap<String, ConcurrentHashMap<String, String>> = ConcurrentHashMap()

    // Host-side API exposed to guests under platform.kv
    class KvApi(private val stores: Map<String, ConcurrentHashMap<String, String>>) {
        // For simplicity we choose the first available store as default when multiple exist.
        private fun defaultStore(): ConcurrentHashMap<String, String> = stores.values.firstOrNull()
            ?: throw IllegalStateException("No KV resource is owned by this function")

        // Default operations (no name): operate on default store
        fun get(key: String): String? = defaultStore()[key]
        fun put(key: String, value: String) { defaultStore()[key] = value }
        fun delete(key: String) { defaultStore().remove(key) }
        fun keys(): List<String> = defaultStore().keys().toList()

        // Named operations when multiple KV stores exist (optional)
        fun getFrom(resourceId: String, key: String): String? = stores[resourceId]?.get(key)
        fun putTo(resourceId: String, key: String, value: String) { stores[resourceId]?.put(key, value) }
        fun deleteFrom(resourceId: String, key: String) { stores[resourceId]?.remove(key) }
        fun listResources(): List<String> = stores.keys.toList()
    }

    // Placeholder for SQL API; not implemented to avoid extra dependencies in this demo.
    class SqlApi {
        fun query(sql: String): List<Map<String, Any?>> {
            throw UnsupportedOperationException("sql resource type is not implemented in this demo")
        }
        fun exec(sql: String): Int {
            throw UnsupportedOperationException("sql resource type is not implemented in this demo")
        }
    }

    // Platform object exposed into the event
    class Platform(
        val kv: KvApi?,
        val sql: SqlApi? = null
    ) {
        fun hasKv(): Boolean = kv != null
        fun kvGet(key: String): String? = kv?.get(key)
        fun kvPut(key: String, value: String) { kv?.put(key, value) }
        fun kvDelete(key: String) { kv?.delete(key) }
        fun kvKeys(): List<String> = kv?.keys() ?: emptyList()
    }

    /**
     * Build a Platform handle for the given function, containing only the resources the function owns.
     * For KV, we may have multiple resources; methods without resource id refer to an arbitrary default (first).
     */
    fun platformForFunction(functionId: String): Platform? {
        // First try in-memory index for speed and freshness
        val ids: Set<String> = ownerIndex[functionId]?.toSet() ?: emptySet()
        val owned: List<ResourceRecord> = if (ids.isNotEmpty()) {
            ids.mapNotNull { load(it) }
        } else {
            // Fallback to scanning disk (e.g., after restart)
            list().filter { it.owners.contains(functionId) }
        }
        if (owned.isEmpty()) return null

        val kvOwned = owned.filter { it.type == "kv" }
        val kvMap: Map<String, ConcurrentHashMap<String, String>> = kvOwned.associate { rec ->
            rec.id to kvStores.computeIfAbsent(rec.id) { ConcurrentHashMap() }
        }
        val kvApi = if (kvMap.isNotEmpty()) KvApi(kvMap) else null
        val sqlApi: SqlApi? = if (owned.any { it.type == "sql" }) SqlApi() else null
        return Platform(kv = kvApi, sql = sqlApi)
    }

    private fun index(record: ResourceRecord) {
        for (owner in record.owners) {
            ownerIndex.compute(owner) { _, set ->
                val s = set ?: mutableSetOf()
                s.add(record.id)
                s
            }
        }
    }
}
