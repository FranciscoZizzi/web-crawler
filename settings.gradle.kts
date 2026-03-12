plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
    kotlin("jvm") version "1.9.22" apply false
}
include("crawler-core")
include("crawler-api")
