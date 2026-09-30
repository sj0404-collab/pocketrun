package dev.pocketrun.runtime.js

import dev.pocketrun.core.Workspace
import dev.pocketrun.runtime.ExecRequest
import dev.pocketrun.runtime.RuntimeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The syntax the engine has to accept.
 *
 * Every case here is a hard parse error on Rhino 1.8.1, which is what made modern
 * npm packages unusable: `npm install` would fetch a package and then the runtime
 * would refuse to load it with "Cannot parse module". A test that only ever loads
 * hand-written ES5 would not notice that regression coming back, so the modern
 * forms are asserted directly.
 */
class ModernSyntaxTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bootSource(): String = listOf(
        File("src/main/assets/node/boot.js"),
        File("app/src/main/assets/node/boot.js"),
    ).firstOrNull { it.isFile }?.readText(Charsets.UTF_8) ?: error("boot.js not found")

    private fun run(workspace: Workspace, script: String): dev.pocketrun.runtime.ExecResult {
        val main = File(workspace.root, "main.js")
        main.writeText(script, Charsets.UTF_8)
        return JsRuntime(bootSource(), workspace, maxRunMs = 30_000)
            .executeSync(ExecRequest(RuntimeKind.NODE, main.absolutePath, emptyList(), workspace.root), 20_000)
    }

    private fun assertPrints(workspace: Workspace, script: String, vararg expected: String): String {
        val r = run(workspace, script)
        assertEquals("stderr: ${r.stderr}", 0, r.exitCode)
        for (e in expected) {
            assertTrue("stdout: ${r.stdout}\nstderr: ${r.stderr}", r.stdout.contains(e))
        }
        return r.stdout
    }

    @Test
    fun classesWithInheritanceAndStatics() {
        val ws = Workspace.at(tmp.newFolder())
        assertPrints(
            ws,
            """
            class Shape {
                constructor(n) { this.n = n; }
                area() { return 0; }
                get label() { return 'shape:' + this.n; }
                static of(n) { return new Shape(n); }
            }
            class Square extends Shape {
                constructor(s) { super('sq' + s); this.s = s; }
                area() { return this.s * this.s; }
            }
            console.log(new Square(4).area());
            console.log(new Square(4).label);
            console.log(Shape.of(1).n);
            console.log(new Square(2) instanceof Shape);
            """.trimIndent(),
            "16", "shape:sq4", "1", "true",
        )
    }

    @Test
    fun destructuringSpreadAndTemplateLiterals() {
        val ws = Workspace.at(tmp.newFolder())
        assertPrints(
            ws,
            """
            const { a, b = 7, ...rest } = { a: 1, c: 3, d: 4 };
            const [x, , y = 9] = [10, 20];
            const merged = { ...{ p: 1 }, q: 2 };
            const name = 'world';
            console.log(`${'$'}{name}/${'$'}{a}/${'$'}{b}/${'$'}{JSON.stringify(rest)}`);
            console.log(x, y);
            console.log(JSON.stringify(merged));
            """.trimIndent(),
            "world/1/7/{\"c\":3,\"d\":4}", "10 9", "{\"p\":1,\"q\":2}",
        )
    }

    @Test
    fun generatorsAndIterators() {
        val ws = Workspace.at(tmp.newFolder())
        assertPrints(
            ws,
            """
            function* upto(n) { for (let i = 1; i <= n; i++) yield i * i; }
            const out = [...upto(4)].join(',');
            console.log(out);
            const m = new Map([['k', 1], ['j', 2]]);
            console.log([...m.keys()].join(''), m.size);
            console.log(typeof m.get === 'function' ? 'has-get' : 'no');
            console.log([...new Set([1, 1, 2, 2, 3])].join(''));
            """.trimIndent(),
            "1,4,9,16", "kj 2", "has-get", "123",
        )
    }

    /**
     * The async rewrite that used to happen here stripped `async` from every
     * function in a module, which silently broke anything containing `await`.
     */
    @Test
    fun asyncAwaitInsideAModule() {
        val ws = Workspace.at(tmp.newFolder())
        File(ws.root, "worker.js").writeText(
            """
            class Worker {
                constructor(v) { this.v = v; }
                async double() {
                    await null;
                    const extra = await Promise.resolve(5);
                    return this.v * 2 + extra;
                }
            }
            module.exports = Worker;
            """.trimIndent(),
            Charsets.UTF_8,
        )
        val r = run(
            ws,
            """
            const Worker = require('./worker.js');
            (async function () {
                const w = new Worker(10);
                const out = await w.double();
                console.log('RESULT ' + out);
                console.log('is-promise:' + (w.double() instanceof Promise));
                process.exit(0);
            })();
            """.trimIndent(),
        )
        assertEquals("stderr: ${r.stderr}", 0, r.exitCode)
        assertTrue("stdout: ${r.stdout}", r.stdout.contains("RESULT 25"))
        assertTrue("stdout: ${r.stdout}", r.stdout.contains("is-promise:true"))
    }

    @Test
    fun arrowFunctionsOptionalChainingAndNullishCoalescing() {
        val ws = Workspace.at(tmp.newFolder())
        assertPrints(
            ws,
            """
            const o = { a: { b: null }, f: () => 42 };
            const pick = () => o?.a?.b ?? 'fallback';
            console.log(pick());
            console.log(o?.x?.y ?? 'missing');
            console.log(o.f());
            console.log([1, 2, 3].map(n => n * 2).filter(n => n > 2).join(''));
            """.trimIndent(),
            "fallback", "missing", "42", "46",
        )
    }

    @Test
    fun namedGroupsIteratorsAndLabeledStatementsParse() {
        val ws = Workspace.at(tmp.newFolder())
        assertPrints(
            ws,
            """
            const re = /(?<year>\d{4})-(?<month>\d{2})/;
            const m = re.exec('2026-09');
            console.log(m.groups.year, m.groups.month);
            outer: for (const i of [1, 2]) { for (const j of [1, 2]) { if (j === 2) continue outer; console.log(i, j); } }
            const o = { *[Symbol.iterator]() { yield 'a'; yield 'b'; } };
            console.log([...o].join('-'));
            """.trimIndent(),
            "2026 09", "1 1", "2 1", "a-b",
        )
    }
}
