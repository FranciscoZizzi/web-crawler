plugins {
    kotlin("jvm") version "1.9.22"
    application
}

group = "com.fzizzi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":crawler-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

application { mainClass.set("com.fzizzi.cli.MainKt") }