pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://repo.spring.io/release")
        }
    }
}

rootProject.name = "payment-platform"

include(":backend")
include(":backend:ingest-service")
include(":backend:consumer-worker")
include(":backend:settlement-worker")
include(":backend:gateway")
include(":backend:eureka-server")
include(":backend:monitoring-service")
