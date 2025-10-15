# Python Example App (GraalFaaS)

This example demonstrates minimal Python functions that can be deployed to the GraalFaaS host in this repository.

Contents:
- `handler.py` — the function source defining `handler(event)`.
- `hello-py.json` — the upload manifest the CLI consumes.
- `handler-w-deps.py` — a function that imports a helper from an in-memory dependency.
- `greeter.py` — the helper module used by `handler-w-deps.py`.
- `hello-py-w-deps.json` — manifest wiring the dependency map.

## Deploy (basic)
Prerequisites:
- Internet access for Gradle to resolve dependencies on first run.
- You do NOT need to install GraalVM or JDK 21; Gradle Toolchains will provision them.

Steps:
1) Upload the function asset (stores to `.faas/functions/hello-py-example.json`):
   ./gradlew run --args="upload examples/python/hello-py.json"

2) Start the HTTP server (in a separate terminal):
   `./gradlew run --args="serve --port 8080"`

3) Invoke the function via HTTP:
```shell
   curl -sS -X POST \
     -H 'Content-Type: application/json' \
     -d '{"name":"Bob"}' \
     http://localhost:8080/invoke/hello-py-example
```

Expected response:
   `{"message":"Hello, Bob!"}`

4) List uploaded functions (optional):
   `./gradlew run --args="list"`

## Deploy (with dependencies)
1) Upload the w-deps function (stores to `.faas/functions/python-w-deps.json`):
   ./gradlew run --args="upload examples/python/hello-py-w-deps.json"

2) Invoke the function via HTTP:
```shell
   curl -sS -X POST \
     -H 'Content-Type: application/json' \
     -d '{"name":"Rita"}' \
     http://localhost:8080/invoke/python-w-deps
```

Expected response:
   `{"message":"Hello, Rita!"}`

Notes:
- The host preloads Python dependencies (if provided in the manifest) as ephemeral modules in `sys.modules` prior to evaluating your function, so standard `import` works.
- The manifest uses sourceFile paths relative to the project root (as required by the current CLI implementation).
