plugins {
    id("com.android.application")
}

apply(from = rootProject.file("gradle/plugin-release-signing.gradle"))

android {
    namespace = "com.volund.nexus.plugin.shoplist"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.volund.nexus.plugin.shoplist"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

val sdkVersion = providers.gradleProperty("sdkVersion").orElse("sdk-v0.1.1")

dependencies {
    // Published Rokid Nexus bus-client SDK (JitPack). Standalone build — no monorepo checkout needed.
    implementation("com.github.Anezium.Rokid-Nexus:bus-client:${sdkVersion.get()}")
    testImplementation("junit:junit:4.13.2")
}
