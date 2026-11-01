// TrackNYC service: train feed adapters, GTFS loaders, position engine, REST + SSE API.
plugins {
    alias(libs.plugins.spring.boot)
}

description = "TrackNYC — live NYC regional train map backend"

dependencies {
    implementation(project(":live-core:geo"))
    implementation(project(":live-core:feed"))
    implementation(project(":live-core:state"))
    implementation(project(":live-core:push"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation(libs.gtfs.realtime.bindings)
    implementation(libs.protobuf.java) // pin a patched runtime for gtfs-realtime-bindings
    runtimeOnly("org.postgresql:postgresql")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

// `./gradlew :tracknyc-service:bootRun` from the repo root finds ./fixtures.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}

tasks.withType<Test>().configureEach {
    // Tests read fixtures relative to the repository root.
    workingDir = rootProject.projectDir
    systemProperty("tracknyc.fixtures-dir", rootProject.file("fixtures").absolutePath)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("tracknyc-service.jar")
}
