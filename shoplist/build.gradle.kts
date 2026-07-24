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
        versionCode = 7
        versionName = "1.2.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

val sdkVersion = providers.gradleProperty("sdkVersion").orElse("sdk-v0.2.1")

dependencies {
    // Published Rokid Nexus bus-client SDK (JitPack). Standalone build — no monorepo checkout needed.
    implementation("com.github.Anezium.Rokid-Nexus:bus-client:${sdkVersion.get()}")
    // Phone-side OpenAI transcription call + encrypted storage for the API key.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.0.0")
    testImplementation("junit:junit:4.13.2")
}
