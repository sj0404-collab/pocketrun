package dev.pocketrun.agent.opencode

import dev.pocketrun.core.Workspace
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The local session store: create/list/load/save, rename, fork (parent link),
 * delete, latest-for-project and JSON export — the opencode session semantics
 * mapped onto workspace/opencode-data/sessions.
 */
class SessionsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newSessions(): Sessions = Sessions(Workspace.at(tmp.newFolder()))

    private fun history(vararg texts: String): JSONArray {
        val arr = JSONArray()
        texts.forEachIndexed { i, t ->
            arr.put(JSONObject().put("role", if (i % 2 == 0) "user" else "assistant").put("content", t))
        }
        return arr
    }

    @Test
    fun createSaveLoad() {
        val sessions = newSessions()
        val s = sessions.create(project = "demo", title = null)
        assertEquals("Новая сессия", s.info.title)
        s.messages = history("Привет", "Здравствуйте!")
        s.todos = listOf(Sessions.Todo("t1", "шаг 1", "pending"))
        sessions.save(s)

        val loaded = sessions.load(s.info.id)
        assertNotNull(loaded)
        assertEquals(2, loaded!!.messages.length())
        assertEquals(1, loaded.todos.size)
        assertEquals("шаг 1", loaded.todos[0].content)
        assertEquals("demo", loaded.info.project)
    }

    @Test
    fun listSortedByUpdated() {
        val sessions = newSessions()
        val a = sessions.create(project = "p")
        Thread.sleep(5)
        val b = sessions.create(project = "p")
        Thread.sleep(5)
        val c = sessions.create(project = "p")
        val ids = sessions.list().map { it.id }
        assertEquals(listOf(c.info.id, b.info.id, a.info.id), ids)
    }

    @Test
    fun renamePersists() {
        val sessions = newSessions()
        val s = sessions.create(project = null)
        assertTrue(sessions.rename(s.info.id, "  Ревью кода  "))
        assertEquals("Ревью кода", sessions.load(s.info.id)!!.info.title)
        assertFalse(sessions.rename("missing", "x"))
    }

    @Test
    fun forkCopiesAndLinksParent() {
        val sessions = newSessions()
        val parent = sessions.create(project = "demo")
        parent.messages = history("Разбери проект", "Сейчас посмотрю")
        sessions.save(parent)

        val child = sessions.fork(parent.info.id)
        assertNotNull(child)
        assertEquals(parent.info.id, child!!.info.parentID)
        assertEquals(parent.messages.length(), child.messages.length())
        assertEquals("demo", child.info.project)
        // diverging histories: editing the child does not touch the parent
        child.messages.put(JSONObject().put("role", "user").put("content", "а теперь по-другому"))
        sessions.save(child)
        val parentReloaded = sessions.load(parent.info.id)!!
        assertEquals(2, parentReloaded.messages.length())
        assertEquals(3, sessions.load(child.info.id)!!.messages.length())
    }

    @Test
    fun deleteRemovesFile() {
        val sessions = newSessions()
        val s = sessions.create(project = null)
        assertTrue(sessions.delete(s.info.id))
        assertNull(sessions.load(s.info.id))
        assertFalse(sessions.delete(s.info.id))
    }

    @Test
    fun latestForProject() {
        val sessions = newSessions()
        val a = sessions.create(project = "demo")
        Thread.sleep(5)
        val b = sessions.create(project = "other")
        Thread.sleep(5)
        sessions.save(a) // demo is now the most recently updated
        assertEquals(a.info.id, sessions.latest("demo")?.info?.id)
        assertEquals(b.info.id, sessions.latest("other")?.info?.id)
        assertNull(sessions.latest("none"))
    }

    @Test
    fun exportWritesReadableJson() {
        val sessions = newSessions()
        val s = sessions.create(project = "demo")
        s.messages = history("Проверь код")
        sessions.save(s)
        val path = sessions.export(s.info.id)
        assertNotNull(path)
        val text = File(path!!).readText(Charsets.UTF_8)
        assertTrue(text.contains("\"id\""))
        assertTrue(text.contains("Проверь код"))
        assertNull(sessions.export("missing"))
    }

    @Test
    fun corruptedFileIgnored() {
        val ws = Workspace.at(tmp.newFolder())
        val sessions = Sessions(ws)
        val s = sessions.create(project = null)
        val dir = File(ws.root, "opencode-data/sessions")
        File(dir, "${s.info.id}.json").writeText("{not json")
        assertNull(sessions.load(s.info.id))
        assertTrue(sessions.list().isEmpty())
    }
}
