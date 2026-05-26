dependencies {
    implementation(project(":modules:core"))
    implementation(project(":modules:features"))
    implementation(project(":modules:scoring"))
    implementation(project(":modules:rules"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
