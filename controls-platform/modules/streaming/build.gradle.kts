dependencies {
    implementation(project(":modules:core"))
    implementation(project(":modules:decisioning"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
}
