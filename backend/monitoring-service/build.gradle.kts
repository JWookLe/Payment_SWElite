plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    java
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client:4.1.1")

    // For Resilience4j metrics access
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.1.0")

    // HTTP Client for calling other services
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // Prometheus metrics
    implementation("io.micrometer:micrometer-registry-prometheus")

    // MariaDB
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
