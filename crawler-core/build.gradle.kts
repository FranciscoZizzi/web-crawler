plugins {
    kotlin("jvm")
}

group = "com.fzizzi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}