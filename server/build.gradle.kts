import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application { mainClass.set("se.racelink.server.MainKt") }

dependencies {
    implementation(project(":shared"))
    implementation("io.ktor:ktor-server-core:3.1.2")
    implementation("io.ktor:ktor-server-netty:3.1.2")
    implementation("io.ktor:ktor-server-content-negotiation:3.1.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.2")
    implementation("ch.qos.logback:logback-classic:1.5.16")
    testImplementation("io.ktor:ktor-server-test-host:3.1.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.1.20")
}
