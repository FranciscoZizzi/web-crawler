plugins {
    kotlin("jvm")
    application
}

group = "com.fzizzi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation(project(":crawler-core"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

application { mainClass.set("com.fzizzi.api.MainKt") }
