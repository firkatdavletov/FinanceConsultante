import dev.detekt.gradle.Detekt
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("dev.detekt") version "2.0.0-alpha.3"
    jacoco
}

group = "com.firkatdavletov"
version = "0.0.1-SNAPSHOT"
description = "FinanceConsultante"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("com.openai:openai-java:4.61.0")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

val integrationTestSourceSet =
    sourceSets.create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += output + compileClasspath
    }

configurations[integrationTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[integrationTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    add(integrationTestSourceSet.implementationConfigurationName, "org.testcontainers:testcontainers-postgresql")
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs Spring context and integration tests."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
    providers.gradleProperty("testPostgresUrl").orNull?.let {
        systemProperty("test.postgres.url", it)
    }
}

tasks.register<JavaExec>("openAiModelEvaluation") {
    description = "Runs the explicitly enabled paid OpenAI recognition corpus evaluation."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.firkatdavletov.financeconsultante.ai.evaluation.OpenAiModelEvaluationKt")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("spring.profiles.active", "test")
    // System properties override inherited environment flags from a developer's shell.
    systemProperty("app.telegram.enabled", "false")
    systemProperty("app.openai.enabled", "false")
    systemProperty("app.notifications.enabled", "false")
    systemProperty("app.exchange-rates.enabled", "false")

    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

ktlint {
    version.set("1.8.0")
    outputToConsole.set(true)
}

detekt {
    // This release uses the same Kotlin compiler (2.3.21) as the application.
    toolVersion.set("2.0.0-alpha.3")
    buildUponDefaultConfig.set(true)
    config.setFrom(files("config/detekt/detekt.yml"))
    source.setFrom(files("src/main/kotlin", "src/test/kotlin", "src/integrationTest/kotlin"))
}

tasks.withType<Detekt>().configureEach {
    jvmTarget.set("17")
}

jacoco {
    toolVersion = "0.8.15"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test, integrationTest)
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("jacoco")) {
            include("test.exec", "integrationTest.exec")
        },
    )
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.check {
    dependsOn(integrationTest, tasks.jacocoTestReport, "ktlintCheck", "detekt")
}
