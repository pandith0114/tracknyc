// live-core:feed — FeedAdapter contract, polling scheduler, retry/backoff,
// circuit breaker (Resilience4j) and feed-health metrics (Micrometer).
description = "Feed adapters, polling, resilience and feed-health tracking"

dependencies {
    api("io.micrometer:micrometer-core")
    api(libs.resilience4j.circuitbreaker)
    api(libs.resilience4j.retry)
    implementation(libs.resilience4j.micrometer)
    implementation("org.slf4j:slf4j-api")
}
