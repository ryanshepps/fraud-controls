plugins {
    application
}

dependencies {
    implementation(project(":modules:api"))
    implementation(project(":modules:core"))
    implementation(project(":modules:decisioning"))
    implementation(project(":modules:features"))
    implementation(project(":modules:observability"))
    implementation(project(":modules:persistence"))
    implementation(project(":modules:rules"))
    implementation(project(":modules:scoring"))
    implementation(project(":modules:streaming"))
    implementation("io.ktor:ktor-server-core-jvm:3.0.3")
    implementation("io.ktor:ktor-server-netty-jvm:3.0.3")
    implementation("io.micrometer:micrometer-registry-prometheus:1.16.5")
    implementation("org.apache.kafka:kafka-clients:3.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.snakeyaml:snakeyaml-engine:2.9")
    implementation("redis.clients:jedis:5.2.0")
    implementation("software.amazon.awssdk:dynamodb:2.29.6")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.fraudcontrols.demo.ControlsDemoApplicationKt")
}
