dependencies {
    implementation(project(":modules:core"))
    implementation(project(":modules:decisioning"))
    implementation(project(":modules:rules"))
    implementation("org.apache.kafka:kafka-clients:3.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
}
