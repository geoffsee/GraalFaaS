# JavaScript Example App (GraalFaaS)

This example demonstrates a minimal JavaScript function that can be deployed to the GraalFaaS host in this repository.

Contents:
- `handler.js` — the function source exporting a global `handler(event)`.
- `hello-js.json` — the upload manifest the CLI consumes.

## Deploy
Prerequisites:
- Internet access for Gradle to resolve dependencies on first run.
- You do NOT need to install GraalVM or JDK 21; Gradle Toolchains will provision them.

Steps:
1) Upload the function asset (stores to `.faas/functions/hello-js-example.json`):
   ./gradlew run --args="upload examples/javascript/hello-js.json"

2) Start the HTTP server (in a separate terminal):
   ./gradlew run --args="serve --port 8080"

3) Invoke the function via HTTP:
   curl -sS -X POST \
     -H 'Content-Type: application/json' \
     -d '{"name":"Alice"}' \
     http://localhost:8080/invoke/hello-js-example

Expected response:
   {"message":"Hello, Alice!"}

4) List uploaded functions (optional):
   ./gradlew run --args="list"

Notes:
- The manifest uses sourceFile paths relative to the project root (as required by the current CLI implementation).
- For ES modules, set `jsEvalAsModule: true` in the manifest and export a named `handler` from your module.
