plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "com.walletledger"
version = "0.0.1-SNAPSHOT"
description = "wallet-ledger-system"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

extra["springModulithVersion"] = "2.1.0"

dependencies {
    // Spring Boot Starters
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.data.jdbc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)

    // Spring Modulith
    implementation(platform("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}"))
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.spring.modulith.starter.jdbc)

    // Kotlin & Functional Programming (Arrow)
    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.arrow.core)
    implementation(libs.arrow.fx.coroutines)

    // Database & Migrations
    runtimeOnly(libs.postgresql)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)

    // Messaging
    implementation(libs.spring.kafka)

    // Testing
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.data.jdbc.test)
    testImplementation(libs.spring.boot.starter.actuator.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.spring.kafka.test)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
