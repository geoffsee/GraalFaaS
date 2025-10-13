# GraalFaaS

A minimal polyglot Function‑as‑a‑Service (FaaS) built on top of GraalVM's Polyglot API.

This repository demonstrates how to embed GraalVM languages and execute user functions in a sandboxed context.
Currently supported guest language(s):
- JavaScript (GraalJS).
- Python (GraalPython).

You are “done” when each supported language has at least one example function that runs successfully. This repo ships with a working JavaScript example and a unit test that executes it end‑to‑end.

## Prerequisites
- JDK (Gradle Toolchains will auto‑provision the right JDK).
- Internet access for Gradle to download dependencies from Maven Central.
- You do NOT need to install GraalVM separately: the build depends on the Graal Polyglot SDK and GraalJS engine artifacts from Maven Central.

## Run the demo
- Run `./gradlew run` to build and run the application. You should see output similar to:

```
--- GraalFaaS Demo ---
JavaScript handler result: {message=Hello, World!}
```

The demo invokes the JavaScript function defined in `app/src/main/resources/functions/js/hello.js`:

```javascript
function handler(event) {
  return { message: `Hello, ${event.name}!` };
}
```

## Run tests
- Run `./gradlew check` to run all checks, including the polyglot test `PolyglotFaasTest` that executes the JavaScript handler with an input and asserts the response.

## Project layout
- `app/` – the FaaS host and examples
  - `src/main/kotlin/Faas.kt` – Polyglot invoker that evaluates source and calls a named function with an event object.
  - `src/main/kotlin/App.kt` – CLI demo that invokes the JavaScript example.
  - `src/main/resources/functions/js/hello.js` – JavaScript example function.
  - `src/test/kotlin/PolyglotFaasTest.kt` – test that verifies the example runs successfully.
- `utils/` – a small utility module (used by the original template; not required by the FaaS logic).

## Adding more languages
The FaaS is language‑agnostic: pass `languageId` (e.g., `js`) and the function source. To add a new language:
1. Add the language engine dependency in `gradle/libs.versions.toml` and in `app/build.gradle.kts`.
   - Example for JavaScript already added: `org.graalvm.js:js` (version aligned via `graalvm` in the version catalog).
2. Provide a sample function file under `app/src/main/resources/functions/<lang>/...` that exports a `handler(event)` function.
3. Invoke it via `PolyglotFaaS.invoke()` from code or tests, and add a unit test asserting the expected result.

Refer to GraalVM embedding docs for language IDs and options: https://www.graalvm.org/latest/reference-manual/embed-languages

## Build basics
This project uses [Gradle](https://gradle.org/).

Common commands:
- `./gradlew run` – build and run the CLI demo.
- `./gradlew build` – build everything.
- `./gradlew check` – run tests.
- `./gradlew clean` – clean build outputs.

Notes:
- The build uses a version catalog (see `gradle/libs.versions.toml`) and a shared convention plugin in `buildSrc`.
- Repositories are configured to Maven Central via `settings.gradle.kts`.

## Using dependencies in guest code (JavaScript)
PolyglotFaaS supports simple in-memory module dependencies for JavaScript. Provide a map of moduleName -> source in InvocationRequest.dependencies. A lightweight CommonJS-style `require(name)` will be injected so your handler can import those modules.

Example:

- Dependency module (app/src/main/resources/functions/js/lib/greeter.js):
```javascript
module.exports = {
  greet: function(name) { return `Hello, ${name}!`; }
};
```

- Handler using the dependency (app/src/main/resources/functions/js/hello-dep.js):
```javascript
const { greet } = require('greeter');
function handler(event) { return { message: greet(event.name) }; }
```

- Host invocation:
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

Notes:
- This loader only resolves modules from the provided in-memory map; there is no network or filesystem access.
- Module format is CommonJS: each module gets `(exports, module, require)` and should assign to `module.exports` or `exports`.

## Using ES modules (JavaScript)
PolyglotFaaS can evaluate your handler as an ES module. Export a function named `handler(event)` from your module and set `jsEvalAsModule = true` in the invocation request.

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
- The ES module is evaluated with GraalJS' module semantics and its namespace is used to resolve the exported `handler`.
- Current in-memory dependency support is CommonJS-only (via `require(name)`). Importing other ES modules with `import` specifiers is not yet supported by this minimal loader.
