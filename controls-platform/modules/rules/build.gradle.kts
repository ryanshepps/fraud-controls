dependencies {
    implementation(project(":modules:core"))
    implementation("org.snakeyaml:snakeyaml-engine:2.9")

    testImplementation(kotlin("test"))
    testImplementation(project(":modules:features"))
}
