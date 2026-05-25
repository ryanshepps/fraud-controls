import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.0.21" apply false
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
    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
