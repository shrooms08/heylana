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
 * Who the app says it is when it opens the wallet (Mobile Wallet Adapter's identity
 * address). The Wallet's own scanner blocks every mainnet transaction from a
 * workers.dev host — "Scam site detected. We blocked the transaction." — so the
 * identity is Heylana's own domain, which serves /.well-known/assetlinks.json and
 * heylana-mark.png:
 *
 *     heylana.identityUrl=https://heylana.app
 *
 * Unset, it falls back to the proxy address, which is what it always was.
 */
val identityUrl: String = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}.getProperty("heylana.identityUrl").orEmpty().trim().trimEnd('/').ifEmpty { proxyUrl }

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

/**
 * Crash reports. The Sentry DSN is one line in local.properties, and empty means
 * Sentry is off. Release builds only, unless heylana.sentryDebug=true says a debug
 * build should report too (for the test crash).
 *
 *     heylana.sentryDsn=https://…@….ingest.sentry.io/…
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val sentryDsn: String = localProperties.getProperty("heylana.sentryDsn").orEmpty().trim()
val sentryInDebug: Boolean = localProperties.getProperty("heylana.sentryDebug").orEmpty().trim() == "true"

/**
 * The release signing key, from local.properties (or -P for a one-off build). The
 * keystore lives outside the repo, at ~/.heylana/release.keystore by convention:
 *
 *     heylana.keystore=~/.heylana/release.keystore
 *     heylana.keystorePass=…
 *     heylana.keyAlias=heylana
 *     heylana.keyPass=…
 *
 * Missing, and a release build still compiles, unsigned; scripts/release.sh refuses it.
 */
fun releaseSetting(key: String): String? =
    (providers.gradleProperty(key).orNull ?: localProperties.getProperty(key))?.trim()?.takeIf { it.isNotEmpty() }
val releaseKeystore: File? = releaseSetting("heylana.keystore")
    ?.let { File(if (it.startsWith("~/")) System.getProperty("user.home") + it.substring(1) else it) }
    ?.takeIf { it.isFile }

android {
    namespace = "xyz.heylana.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "xyz.heylana.app"
        minSdk = 31
        targetSdk = 37
        versionCode = 7
        versionName = "1.0.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "PROXY_URL", "\"$proxyUrl\"")
        buildConfigField("String", "IDENTITY_URL", "\"$identityUrl\"")
        buildConfigField("String", "LISTEN_URL", "\"$listenUrl\"")
        buildConfigField("String", "SKILLS_INDEX_URL", "\"$skillsIndexUrl\"")
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")
        buildConfigField("boolean", "SENTRY_IN_DEBUG", "$sentryInDebug")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseSetting("heylana.keystorePass")
                keyAlias = releaseSetting("heylana.keyAlias")
                keyPassword = releaseSetting("heylana.keyPass")
            }
        }
    }

    buildTypes {
        release {
            // R8: shrink and optimise. Keep rules for the libraries that need them are
            // in src/main/keepRules/heylana.keep; the default optimize rules come too.
            optimization {
                enable = true
            }
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
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
    implementation(libs.mwa.clientlib.ktx) {
        // Its published dependencies wrongly include androidx.test, which would put
        // test activities in the release manifest. None of its classes use them.
        exclude(group = "androidx.test")
        exclude(group = "androidx.test.ext")
        exclude(group = "androidx.test.services")
    }
    implementation(libs.sol4k)
    implementation(libs.sentry.android.core)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}