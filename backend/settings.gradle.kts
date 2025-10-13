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

include("ingest-service")
include("consumer-worker")
