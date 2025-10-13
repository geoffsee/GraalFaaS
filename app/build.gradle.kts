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

    // JSON parsing/serialization (JSON + JSONC via Jackson with comments enabled)
    implementation(libs.jacksonDatabind)
    implementation(libs.jacksonKotlin)

    testImplementation(kotlin("test"))
}

application {
    // Define the Fully Qualified Name for the application main class
    // (Note that Kotlin compiles `App.kt` to a class with FQN `com.example.app.AppKt`.)
    mainClass = "ltd.gsio.app.AppKt"
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
            "-XX:+UseG1GC"
        )
        creationTime = "USE_CURRENT_TIMESTAMP"
    }
}
