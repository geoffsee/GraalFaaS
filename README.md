# GraalFaaS

```shell
no shame
```

A minimal polyglot Function‑as‑a‑Service (FaaS) built on GraalVM’s Polyglot API. It demonstrates how to embed GraalVM languages and execute user functions in an isolated context from a Kotlin host.

Supported guest languages:
- JavaScript (GraalJS)
- Python (GraalPython)


## Quick start
Prerequisites:
- Internet access for Gradle to resolve dependencies from Maven Central.
- You do NOT need a local GraalVM installation. You also don’t need a local JDK 21; Gradle Toolchains will provision it automatically.

Run the demo:
- `./gradlew run`

You should see output like:
```
--- GraalFaaS Demo ---
JavaScript handler result: {message=Hello, World!}
```

The demo invokes the JavaScript function at `app/src/main/resources/functions/js/hello.js`:
```javascript
function handler(event) {
  return { message: `Hello, ${event.name}!` };
}
```


## Testing
- All tests: `./gradlew check` or `./gradlew :app:test`
- One test class: `./gradlew :app:test --tests "ltd.gsio.app.PolyglotFaasTest"`
- One test method: `./gradlew :app:test --tests "ltd.gsio.app.PolyglotFaasTest.javascript ES module handler returns greeting"`

Tip: Use println/System.out.println in tests for ad‑hoc debugging. Gradle is configured to show PASSED/FAILED/SKIPPED events.


## Using the FaaS from Kotlin
PolyglotFaaS constructs a fresh Graal Context per invocation and calls a named function with an event object.

Basic JavaScript example:
```kotlin
val jsSource = loadResource("/functions/js/hello.js")
val result = PolyglotFaaS.invoke(
    PolyglotFaaS.InvocationRequest(
        languageId = "js",
        sourceCode = jsSource,
        functionName = "handler",
        event = mapOf("name" to "World")
    )
)
// result is a Map: {message=Hello, World!}
```

Python example:
```python
# app/src/main/resources/functions/py/hello.py

def handler(event):
    name = event.get("name", "World")
    return f"Hello, {name}!"
```
```kotlin
val pySource = loadResource("/functions/py/hello.py")
val pyResult = PolyglotFaaS.invoke(
    PolyglotFaaS.InvocationRequest(
        languageId = "python",
        sourceCode = pySource,
        functionName = "handler",
        event = mapOf("name" to "PyUser")
    )
)
// pyResult is a String: "Hello, PyUser!"
```

Notes about Python:
- The host auto‑creates a Python trampoline `__faas_invoke__` so your `handler(event)` can be called without passing host objects into Python.
- Results are converted back to host types: primitives/strings; dicts→Map; lists→List; other objects→string fallback.


## JavaScript dependencies (CommonJS‑style in‑memory modules)
You can supply in‑memory module sources and `require(name)` them from your handler.

Dependency module (app/src/main/resources/functions/js/lib/greeter.js):
```javascript
module.exports = {
  greet: function(name) { return `Hello, ${name}!`; }
};
```

Handler using the dependency (app/src/main/resources/functions/js/hello-dep.js):
```javascript
const { greet } = require('greeter');
function handler(event) { return { message: greet(event.name) }; }
```

Host invocation:
```kotlin
val main = loadResource("/functions/js/hello-dep.js")
val dep = loadResource("/functions/js/lib/greeter.js")
val result = PolyglotFaaS.invoke(
    PolyglotFaaS.InvocationRequest(
        languageId = "js",
        sourceCode = main,
        event = mapOf("name" to "World"),
        dependencies = mapOf("greeter" to dep)
    )
)
```

Important:
- The loader only resolves modules from the provided in‑memory map; there is no filesystem or network access.
- Modules use a CommonJS shape: each module receives `(exports, module, require)` and should set `module.exports` or `exports`.


## JavaScript ES modules
You can evaluate your handler as an ES module. Export a named `handler(event)` and set `jsEvalAsModule = true`.

Example module (app/src/main/resources/functions/js/hello-esm.mjs):
```javascript
export function handler(event) {
  return { message: `Hello, ${event.name}!` };
}
```

Host invocation:
```kotlin
val jsSource = loadResource("/functions/js/hello-esm.mjs")
val result = PolyglotFaaS.invoke(
    PolyglotFaaS.InvocationRequest(
        languageId = "js",
        sourceCode = jsSource,
        jsEvalAsModule = true,
        event = mapOf("name" to "World")
    )
)
```

Notes:
- The module is evaluated with GraalJS module semantics; the module namespace is used to resolve the exported `handler`.
- In‑memory dependency support is currently CommonJS‑only via `require(name)`; `import` of other ES modules isn’t wired by this minimal loader.


## Project layout
- `app/` – FaaS host and examples
  - `src/main/kotlin/Faas.kt` – Polyglot invoker and marshalling logic
  - `src/main/kotlin/App.kt` – CLI demo entrypoint
  - `src/main/resources/functions/js/...` – JavaScript examples
  - `src/main/resources/functions/py/...` – Python examples
  - `src/test/kotlin/PolyglotFaasTest.kt` – polyglot unit tests
  - `src/test/kotlin/AppIntegrationTest.kt` – CLI demo integration test
- `utils/` – small utility module (not required by FaaS logic)


## Build basics and tooling
This is a Gradle multi‑project build. Key points:
- JDK 21 toolchain is enforced by the convention plugin (`buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts → jvmToolchain(21)`).
- GraalVM engines come from Maven Central via the version catalog (`gradle/libs.versions.toml`):
  - org.graalvm.polyglot:polyglot
  - org.graalvm.js:js
  - org.graalvm.python:python
- The `:app` module depends on these via `implementation(libs.polyglot/js/python)`.

Common commands:
- `./gradlew run` – build and run the CLI demo
- `./gradlew build` – full build
- `./gradlew check` – run tests
- `./gradlew clean` – clean outputs

Reference: GraalVM embedding docs https://www.graalvm.org/latest/reference-manual/embed-languages


## Security note
For simplicity, the demo enables `allowAllAccess(true)` on the Graal context. For a hardened environment you should:
- Disable broad host access and selectively expose only what’s needed.
- Avoid injecting `require` unless you need in‑memory deps; it only resolves the provided map and cannot access the filesystem.
- Curate per‑language options carefully.


## Troubleshooting
- Gradle can’t find a JDK: Let Gradle Toolchains download one (no local JDK 21 required). Ensure internet access on first run.
- Classpath/engine mismatch: Keep the GraalVM artifacts aligned via the single `graalvm` version in `gradle/libs.versions.toml`.
- Tests can’t find resources: Ensure paths start with `/functions/...` and use `getResourceAsStream` with UTF‑8 decoding as in the tests.
