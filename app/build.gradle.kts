import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Secrets/config: config.properties (gitignored) at repo root. Falls back to the example file so
// CI and a fresh clone still build; the example values are placeholders, not working endpoints.
val config = Properties().apply {
    val real = rootProject.file("config.properties")
    val example = rootProject.file("config.example.properties")
    (if (real.exists()) real else example).inputStream().use { load(it) }
}
fun cfg(key: String): String = config.getProperty(key)?.substringBefore('#')?.trim().orEmpty()

android {
    namespace = "com.emotiveautomaton.wristwork"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.emotiveautomaton.wristwork"
        // Pixel Watch 5 on Wear OS 6 (Android 16, API 36). This device only; no backcompat by spec.
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "NTFY_BASE_URL", "\"${cfg("NTFY_BASE_URL")}\"")
        buildConfigField("String", "NTFY_TOKEN", "\"${cfg("NTFY_TOKEN")}\"")
        buildConfigField("String", "TOPIC_TAGS", "\"${cfg("TOPIC_TAGS")}\"")
        buildConfigField("String", "TOPIC_AGENTS", "\"${cfg("TOPIC_AGENTS")}\"")
        buildConfigField("String", "TOPIC_RIG", "\"${cfg("TOPIC_RIG")}\"")
        buildConfigField("String", "TOPIC_PRINTER", "\"${cfg("TOPIC_PRINTER")}\"")
        buildConfigField("String", "PRINTER_HOST", "\"${cfg("PRINTER_HOST")}\"")
        buildConfigField("String", "PRINTER_API_KEY", "\"${cfg("PRINTER_API_KEY")}\"")
        buildConfigField("String", "TOPIC_ACKS", "\"${cfg("TOPIC_ACKS")}\"")
        buildConfigField("String", "TOPIC_FLAGS", "\"${cfg("TOPIC_FLAGS")}\"")
        buildConfigField("String", "TOPIC_HEALTH", "\"${cfg("TOPIC_HEALTH")}\"")
        buildConfigField("String", "TOPIC_PROMPTS", "\"${cfg("TOPIC_PROMPTS")}\"")
        buildConfigField("int", "CUE_DELAY_MIN", cfg("CUE_DELAY_MIN").ifEmpty { "30" })
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.complications.data.source.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.work.runtime.ktx)
    implementation(libs.wear.remote.interactions)
    implementation(libs.health.services.client)
    implementation(libs.guava)
}
