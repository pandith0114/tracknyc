// live-core:push — Server-Sent Events gateway: snapshot on connect, then
// coalesced diffs every N seconds; diff computation is pure Java.
description = "SSE push gateway with snapshot + diff protocol"

dependencies {
    api(project(":live-core:state"))
    api("org.springframework:spring-webmvc")
    api("com.fasterxml.jackson.core:jackson-databind")
    api("io.micrometer:micrometer-core")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    implementation("org.slf4j:slf4j-api")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
}
