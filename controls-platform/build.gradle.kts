import com.diffplug.gradle.spotless.SpotlessExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.0.21" apply false
    id("com.google.protobuf") version "0.9.4" apply false
    id("com.diffplug.spotless") version "8.5.1" apply false
}

apply(plugin = "com.diffplug.spotless")

configure<SpotlessExtension> {
    kotlinGradle {
        target("*.gradle.kts", "modules/**/*.gradle.kts")
        ktlint("1.7.1")
    }
}

allprojects {
    group = "com.fraudcontrols"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    if (path == ":modules") {
        return@subprojects
    }

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    extensions.configure<SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint("1.7.1").editorConfigOverride(
                mapOf(
                    "ktlint_standard_filename" to "disabled",
                ),
            )
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
