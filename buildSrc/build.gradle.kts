plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Gradle no longer runs buildSrc tests on its own; run them whenever buildSrc is rebuilt,
// so CI (a fresh build) always checks the ABI logic the app build relies on.
// (finalizedBy, not dependsOn: the test classpath itself contains the jar.)
tasks.named("jar") { finalizedBy("test") }
