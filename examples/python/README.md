# Python Example App (GraalFaaS)

This example demonstrates a minimal Python function that can be deployed to the GraalFaaS host in this repository.

Contents:
- `handler.py` — the function source defining `handler(event)`.
- `hello-py.json` — the upload manifest the CLI consumes.

## Deploy
Prerequisites:
- Internet access for Gradle to resolve dependencies on first run.
- You do NOT need to install GraalVM or JDK 21; Gradle Toolchains will provision them.

Steps:
1) Upload the function asset (stores to `.faas/functions/hello-py-example.json`):
   ./gradlew run --args="upload examples/python/hello-py.json"

2) Start the HTTP server (in a separate terminal):
   ./gradlew run --args="serve --port 8080"

3) Invoke the function via HTTP:
   curl -sS -X POST \
     -H 'Content-Type: application/json' \
     -d '{"name":"Bob"}' \
     http://localhost:8080/invoke/hello-py-example

Expected response:
   {"message":"Hello, Bob!"}

4) List uploaded functions (optional):
   ./gradlew run --args="list"

Notes:
- The host auto-creates a Python trampoline that calls `handler(event)` with a dict built from the JSON request body.
- The manifest uses sourceFile paths relative to the project root (as required by the current CLI implementation).
