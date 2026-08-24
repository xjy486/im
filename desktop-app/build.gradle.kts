import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

group = "com.jitong.im"
version = project.findProperty("jitongVersion")?.toString() ?: "1.0.0"


kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        kotlin.srcDir("../client-shared/src/main/kotlin")
    }
}

compose.desktop {
    application {
        mainClass = "com.jitong.im.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            macOS {
                bundleID = "com.jitong.im.desktop"
                packageName = "Jitong"
            }
        }
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.h2database:h2:2.3.232")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    useJUnitPlatform()
}
