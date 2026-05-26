dependencies {
    implementation(project(":modules:core"))
    implementation(project(":modules:decisioning"))
    implementation(project(":modules:features"))
    implementation(project(":modules:rules"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("redis.clients:jedis:5.2.0")
    implementation("software.amazon.awssdk:dynamodb:2.29.6")

    testImplementation(kotlin("test"))
}
