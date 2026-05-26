dependencies {
    implementation(project(":modules:core"))
    implementation(project(":modules:features"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.snakeyaml:snakeyaml-engine:2.9")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
