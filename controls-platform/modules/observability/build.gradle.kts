dependencies {
    implementation(project(":modules:core"))
    implementation(project(":modules:decisioning"))
    implementation(project(":modules:rules"))
    implementation(project(":modules:scoring"))
    implementation("io.micrometer:micrometer-core:1.16.5")
    implementation("io.micrometer:micrometer-registry-prometheus:1.16.5")
    implementation("org.apache.kafka:kafka-clients:3.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
