import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Where Heylana's proxy lives. It is one line in local.properties, which is not
 * in git, so the address stays on the machine that built the app:
 *
 *     heylana.proxyUrl=https://heylana-proxy.<account>.workers.dev
 *
 * Empty means "not set up yet", and the app says so rather than guessing.
 */
val proxyUrl: String = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}.getProperty("heylana.proxyUrl").orEmpty().trim().trimEnd('/')

/**
 * Debug builds only: a stand-in for Deepgram's listening socket, so a refused or
 * silent socket can be tested without calling Deepgram. Empty means Deepgram.
 *
 *     heylana.listenUrl=ws://127.0.0.1:8799/v1/listen
 */
val listenUrl: String = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}.getProperty("heylana.listenUrl").orEmpty().trim()

/**
 * Where "Get more" on the Skills screen reads the public skills index. A Gradle
 * property wins (-Pheylana.skillsIndexUrl=… for a one-off test build), then
 * local.properties, then the public repo the index is meant to live in.
 */
val skillsIndexUrl: String = (providers.gradleProperty("heylana.skillsIndexUrl").orNull
    ?: Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }.getProperty("heylana.skillsIndexUrl")
    ?: "https://raw.githubusercontent.com/shrooms08/heylana-skills/main/index.json").trim()

android {
    namespace = "xyz.heylana.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "xyz.heylana.app"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "PROXY_URL", "\"$proxyUrl\"")
        buildConfigField("String", "LISTEN_URL", "\"$listenUrl\"")
        buildConfigField("String", "SKILLS_INDEX_URL", "\"$skillsIndexUrl\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

/**
 * The built-in skills are the files in skills/ at the top of the repo, shipped as
 * assets at the root of the APK so the app and the repo can never disagree.
 */
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory(rootProject.file("skills").absolutePath)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.mwa.clientlib.ktx)
    implementation(libs.sol4k)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}