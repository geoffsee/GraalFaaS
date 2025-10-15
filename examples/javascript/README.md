# JavaScript Example App (GraalFaaS)

This example demonstrates minimal JavaScript functions that can be deployed to the GraalFaaS host in this repository.

Contents:
- `handler.js` — the function source exporting a global `handler(event)`.
- `hello-js.json` — the upload manifest the CLI consumes.
- `handler-w-deps.js` — a function that requires an in-memory dependency via `require('greeter')`.
- `greeter.js` — the CommonJS-style dependency used by `handler-w-deps.js`.
- `hello-js-w-deps.json` — manifest wiring the dependency map.

## Deploy (basic)
Prerequisites:
- Internet access for Gradle to resolve dependencies on first run.
- You do NOT need to install GraalVM or JDK 21; Gradle Toolchains will provision them.

Steps:
1) Upload the function asset (stores to `.faas/functions/hello-js-example.json`):
   `./gradlew run --args="upload examples/javascript/hello-js.json"`

2) Start the HTTP server (in a separate terminal):
   `./gradlew run --args="serve --port 8080"`

3) Invoke the function via HTTP:
```shell
   curl -sS -X POST \
     -H 'Content-Type: application/json' \
     -d '{"name":"Alice"}' \
     http://localhost:8080/invoke/hello-js-example
```

Expected response:
   `{"message":"Hello, Alice!"}`

4) List uploaded functions (optional):
   `./gradlew run --args="list"`

## Deploy (with dependencies)
1) Upload the w-deps function (stores to `.faas/functions/javascript-w-deps.json`):
   `./gradlew run --args="upload examples/javascript/hello-js-w-deps.json"`

2) Invoke the function via HTTP:
```shell
   curl -sS -X POST \
     -H 'Content-Type: application/json' \
     -d '{"name":"Dana"}' \
     http://localhost:8080/invoke/javascript-w-deps
```

Expected response:
   `{"message":"Hello, Dana!"}`

Notes:
- The manifest uses sourceFile paths relative to the project root (as required by the current CLI implementation).
- JavaScript dependencies are supplied as an in-memory map and loaded by a tiny CommonJS-style `require` shim provided by the host.
- For ES modules, set `jsEvalAsModule: true` in the manifest and export a named `handler` from your module.
