plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")

    // Apply the Application plugin to add support for building an executable JVM application.
    application

    // Container image build with Jib
    alias(libs.plugins.jib)
}

dependencies {
    // Project "app" depends on project "utils". (Project paths are separated with ":", so ":utils" refers to the top-level "utils" project.)
    implementation(project(":utils"))

    // GraalVM Polyglot SDK and JavaScript engine
    implementation(libs.polyglot)
    implementation(libs.js)
    implementation(libs.python)
    implementation(libs.ruby)

    // JSON parsing/serialization via Gson
    implementation(libs.gson)

    testImplementation(kotlin("test"))
}

application {
    // Define the Fully Qualified Name for the application main class
    // (Note that Kotlin compiles `App.kt` to a class with FQN `com.example.app.AppKt`.)
    mainClass = "ltd.gsio.app.AppKt"

    // Ensure Graal/Truffle native access is permitted on JDK 21 when running via `run`
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        // A conservative open that avoids reflective access issues in some environments
        "--add-opens=java.base/java.lang=ALL-UNNAMED"
    )
}

jib {
    from {
        image = "gcr.io/distroless/java21-debian12:nonroot"
    }
    to {
        image = "ghcr.io/geoffsee/graalfaas:latest"
        tags = setOf("latest")
    }
    container {
        mainClass = "ltd.gsio.app.AppKt"
        args = listOf("serve")
        ports = listOf("8080")
        jvmFlags = listOf(
            "-XX:MaxRAMPercentage=75.0",
            "-XX:+UseG1GC",
            "--enable-native-access=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED"
        )
        creationTime = "USE_CURRENT_TIMESTAMP"
    }
}

// Development server task
// Usage: ./gradlew dev -Pport=8080
// If no -Pport is provided, defaults to 8080.
tasks.register<JavaExec>("dev") {
    group = "application"
    description = "Starts the development HTTP server (equivalent to: run --args=\"serve --port <port>\")"
    mainClass.set("ltd.gsio.app.AppKt")
    classpath = sourceSets.main.get().runtimeClasspath
    dependsOn("classes")

    // Force the dev task to use the Gradle Toolchains JDK 21 (avoids environment JAVA_HOME mismatches)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })

    // Ensure Graal/Truffle native access is permitted on JDK 21 when running via `dev`
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED"
    )

    val port = providers.gradleProperty("port").orElse("8080")
    args("serve", "--port", port.get())
}
