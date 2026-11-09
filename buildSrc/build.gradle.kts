plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
}

repositories {
    maven {
        url = uri("https://repo1.maven.org/maven2")
        content {
            includeModule("org.jetbrains.kotlin", "kotlin-compiler-embeddable")
        }
    }
}

apply(from = "../repositories.gradle.kts")

dependencies {
    // Gradle Plugins
    implementation("com.android.tools.build:gradle:8.8.1")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
}
