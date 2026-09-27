package dev.pocketrun.agent.opencode

import dev.pocketrun.core.Workspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * opencode-style sessions: every conversation is stored locally under
 * `<workspace>/opencode-data/sessions/<id>.json` with its title, project,
 * parent (forks), the OpenAI-format message history and the todo list —
 * so a session can be resumed, forked, renamed, exported or deleted.
 */
class Sessions(private val workspace: Workspace) {

    data class Todo(val id: String, val content: String, val status: String) {
        val isActive: Boolean get() = status == "in_progress"
        val isDone: Boolean get() = status == "completed"
    }

    data class SessionInfo(
        val id: String,
        var title: String,
        val project: String?,
        val parentID: String?,
        val createdAt: Long,
        var updatedAt: Long,
        var model: String?,
    )

    data class Session(
        val info: SessionInfo,
        var messages: JSONArray,
        var todos: List<Todo>,
    )

    private val dir: File get() = File(workspace.root, "opencode-data/sessions").apply { mkdirs() }

    fun create(project: String?, title: String? = null, parentID: String? = null, model: String? = null): Session {
        val now = System.currentTimeMillis()
        val info = SessionInfo(
            id = UUID.randomUUID().toString().replace("-", "").take(20),
            title = title?.takeIf { it.isNotBlank() } ?: "Новая сессия",
            project = project,
            parentID = parentID,
            createdAt = now,
            updatedAt = now,
            model = model,
        )
        val session = Session(info, JSONArray(), emptyList())
        save(session)
        return session
    }

    fun list(): List<SessionInfo> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { f -> runCatching { readInfo(f) }.getOrNull() }
            .sortedByDescending { it.updatedAt }

    fun load(id: String): Session? {
        val f = File(dir, "$id.json")
        if (!f.isFile) return null
        return try {
            val json = JSONObject(f.readText(Charsets.UTF_8))
            Session(
                info = SessionInfo(
                    id = json.getString("id"),
                    title = json.optString("title", "Сессия"),
                    project = json.optString("project").takeIf { it.isNotEmpty() && it != "null" },
                    parentID = json.optString("parentID").takeIf { it.isNotEmpty() && it != "null" },
                    createdAt = json.optLong("createdAt"),
                    updatedAt = json.optLong("updatedAt"),
                    model = json.optString("model").takeIf { it.isNotEmpty() && it != "null" },
                ),
                messages = json.optJSONArray("messages") ?: JSONArray(),
                todos = parseTodos(json.optJSONArray("todos")),
            )
        } catch (t: Throwable) {
            null
        }
    }

    fun save(session: Session) {
        session.info.updatedAt = System.currentTimeMillis()
        val todos = JSONArray().apply {
            session.todos.forEach { t ->
                put(JSONObject().put("id", t.id).put("content", t.content).put("status", t.status))
            }
        }
        val json = JSONObject()
            .put("id", session.info.id)
            .put("title", session.info.title)
            .put("project", session.info.project ?: JSONObject.NULL)
            .put("parentID", session.info.parentID ?: JSONObject.NULL)
            .put("createdAt", session.info.createdAt)
            .put("updatedAt", session.info.updatedAt)
            .put("model", session.info.model ?: JSONObject.NULL)
            .put("messages", session.messages)
            .put("todos", todos)
        File(dir, "${session.info.id}.json").writeText(json.toString(), Charsets.UTF_8)
    }

    fun delete(id: String): Boolean = File(dir, "$id.json").delete()

    fun rename(id: String, title: String): Boolean {
        val s = load(id) ?: return false
        s.info.title = title.trim().take(80).ifEmpty { s.info.title }
        save(s)
        return true
    }

    /** Fork: a copy with a new id, parent set, same history. */
    fun fork(id: String): Session? {
        val s = load(id) ?: return null
        val child = create(
            project = s.info.project,
            title = s.info.title + " (форк)",
            parentID = s.info.id,
            model = s.info.model,
        )
        child.messages = JSONArray(s.messages.toString())
        child.todos = s.todos
        save(child)
        return child
    }

    /** Most recently updated session for a project (the `--continue` analog). */
    fun latest(project: String?): Session? =
        list().firstOrNull { it.project == project }?.let { load(it.id) }

    /** Export a session as readable JSON (the `opencode export` analog). */
    fun export(id: String): String? {
        val s = load(id) ?: return null
        val f = File(workspace.root, "opencode-data/exports").apply { mkdirs() }
        val name = "session-${s.info.id}.json"
        f.resolve(name).writeText(pretty(s), Charsets.UTF_8)
        return f.resolve(name).absolutePath
    }

    fun pretty(session: Session): String {
        val json = JSONObject()
            .put("id", session.info.id)
            .put("title", session.info.title)
            .put("project", session.info.project ?: JSONObject.NULL)
            .put("parent", session.info.parentID ?: JSONObject.NULL)
            .put("created", session.info.createdAt)
            .put("updated", session.info.updatedAt)
            .put("todos", JSONArray().apply { session.todos.forEach { put(it.content + " [" + it.status + "]") } })
            .put("messages", session.messages)
        return json.toString(2)
    }

    private fun readInfo(f: File): SessionInfo? {
        val json = JSONObject(f.readText(Charsets.UTF_8))
        if (!json.has("id")) return null
        return SessionInfo(
            id = json.getString("id"),
            title = json.optString("title", "Сессия"),
            project = json.optString("project").takeIf { it.isNotEmpty() && it != "null" },
            parentID = json.optString("parentID").takeIf { it.isNotEmpty() && it != "null" },
            createdAt = json.optLong("createdAt"),
            updatedAt = json.optLong("updatedAt"),
            model = json.optString("model").takeIf { it.isNotEmpty() && it != "null" },
        )
    }

    private fun parseTodos(arr: JSONArray?): List<Todo> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Todo(
                id = o.optString("id").ifEmpty { "t$i" },
                content = o.optString("content"),
                status = o.optString("status", "pending"),
            )
        }
    }
}
