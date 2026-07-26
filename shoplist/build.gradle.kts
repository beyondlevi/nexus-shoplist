plugins {
    id("com.android.application")
}

apply(from = rootProject.file("gradle/plugin-release-signing.gradle"))

android {
    namespace = "com.volund.nexus.plugin.shoplist"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.volund.nexus.plugin.shoplist"
        minSdk = 30
        targetSdk = 36
        versionCode = 11
        versionName = "1.3.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

val sdkVersion = providers.gradleProperty("sdkVersion").orElse("sdk-v0.3.0")

dependencies {
    // Published Rokid Nexus bus-client SDK (JitPack). Standalone build — no monorepo checkout needed.
    implementation("com.github.Anezium.Rokid-Nexus:bus-client:${sdkVersion.get()}")
    testImplementation("junit:junit:4.13.2")
}
