// Root build: shared conventions for every module.
plugins {
    alias(libs.plugins.spring.boot) apply false
}

val springBootBom = "org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    dependencies {
        // Spring Boot's BOM pins versions for Spring, Jackson, Micrometer, Lettuce,
        // Testcontainers, JUnit, Flyway, the PostgreSQL driver, etc.
        "api"(platform(springBootBom))
        "annotationProcessor"(platform(springBootBom))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:unchecked"))
    }

    // Unit tests: fast, no Docker. Anything tagged "docker" (Testcontainers) is excluded.
    tasks.named<Test>("test") {
        useJUnitPlatform { excludeTags("docker") }
        testLogging { events("failed"); showStandardStreams = false }
    }

    // Integration tests: Testcontainers against PostGIS and Redis. Needs a Docker daemon.
    val testSourceSet = the<SourceSetContainer>()["test"]
    tasks.register<Test>("integrationTest") {
        description = "Runs Testcontainers integration tests (requires Docker)."
        group = "verification"
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        useJUnitPlatform { includeTags("docker") }
        shouldRunAfter("test")
        testLogging { events("passed", "failed", "skipped") }
    }
}
