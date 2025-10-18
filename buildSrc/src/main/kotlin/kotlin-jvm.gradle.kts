// The code in this file is a convention plugin - a Gradle mechanism for sharing reusable build logic.
// `buildSrc` is a Gradle-recognized directory and every plugin there will be easily available in the rest of the build.
package buildsrc.convention

import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    // Apply the Kotlin JVM plugin to add support for Kotlin in JVM projects.
    kotlin("jvm")
}

kotlin {
    // Use a specific Java version to make it easier to work in different environments.
    // GraalVM languages currently target LTS JDKs; use 21 for maximum compatibility.
    jvmToolchain(21)
}

tasks.withType<Test>().configureEach {
    // Configure all test Gradle tasks to use JUnitPlatform.
    useJUnitPlatform()

    // Allow Graal/Truffle native access on JDK 21 during tests, to avoid initializer errors.
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED"
    )

    // Log information about all test results, not only the failed ones.
    testLogging {
        events(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED
        )
    }
}
