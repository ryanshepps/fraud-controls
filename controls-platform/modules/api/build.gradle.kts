import com.google.protobuf.gradle.id

plugins {
    id("com.google.protobuf")
}

dependencies {
    implementation(project(":modules:core"))
    implementation(project(":modules:decisioning"))
    implementation(project(":modules:rules"))
    implementation(project(":modules:streaming"))
    implementation("com.google.protobuf:protobuf-kotlin:4.28.3")
    implementation("io.grpc:grpc-kotlin-stub:1.4.1")
    implementation("io.grpc:grpc-netty-shaded:1.68.1")
    implementation("io.grpc:grpc-protobuf:1.68.1")
    implementation("javax.annotation:javax.annotation-api:1.3.2")
    implementation("io.ktor:ktor-server-core-jvm:3.0.3")
    implementation("io.ktor:ktor-server-netty-jvm:3.0.3")
    implementation("org.apache.kafka:kafka-clients:3.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(project(":modules:features"))
    testImplementation(project(":modules:scoring"))
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.0.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

sourceSets {
    main {
        proto {
            srcDir("../../proto")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.28.3"
    }
    plugins {
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpckt")
            }
            task.builtins {
                id("kotlin")
            }
        }
    }
}
