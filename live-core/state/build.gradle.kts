// live-core:state — latest-state store with TTLs and a Pub/Sub publisher.
// Redis implementations plus in-memory implementations for tests / single node.
description = "Latest-state store (Redis, TTL) and Pub/Sub messaging"

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind")
    api("org.springframework.data:spring-data-redis")
    implementation("io.lettuce:lettuce-core")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    testRuntimeOnly("org.slf4j:slf4j-simple")
}
