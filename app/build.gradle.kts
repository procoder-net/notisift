import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Optional build-time defaults from <repo>/.env (gitignored) or the shell environment.
// Anything here can be changed later in the app's Settings screen.
val dotenv: Map<String, String> = rootProject.file(".env").takeIf { it.exists() }?.readLines().orEmpty()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
    .associate { line ->
        val i = line.indexOf('=')
        line.substring(0, i).removePrefix("export ").trim() to line.substring(i + 1).trim().trim('"', '\'')
    }

fun envValue(vararg names: String): Pair<String, String> = names.firstNotNullOfOrNull { name ->
    (dotenv[name]?.takeIf { it.isNotBlank() } ?: System.getenv(name)?.takeIf { it.isNotBlank() })?.let { name to it }
} ?: ("" to "")

fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val keyEnv = envValue(
    "SIFT_API_KEY", "PERPLEXITY_API_KEY", "PPLX_API_KEY", "OPENAI_API_KEY", "ANTHROPIC_API_KEY",
    "GEMINI_API_KEY", "GOOGLE_API_KEY", "OPENROUTER_API_KEY", "GROQ_API_KEY", "JEV_API_KEY",
)

// Release signing from <repo>/keystore.properties (gitignored) or SIFT_KEYSTORE_* environment variables (CI).
// Without either, release builds fall back to the debug key so anyone can still build and try the app.
val signing: Map<String, String> = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }
    ?.let { p -> p.stringPropertyNames().associateWith { p.getProperty(it).trim() } }
    ?: listOf("storeFile", "storePassword", "keyAlias", "keyPassword").mapNotNull { k ->
        System.getenv("SIFT_KEYSTORE_" + k.replace(Regex("([A-Z])"), "_$1").uppercase())?.let { k to it }
    }.toMap()
val hasReleaseKey = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !signing[it].isNullOrBlank() }

android {
    namespace = "io.github.notisift"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.notisift"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "ENV_PROVIDER", quoted(envValue("SIFT_PROVIDER").second))
        buildConfigField("String", "ENV_BASE_URL", quoted(envValue("SIFT_BASE_URL").second))
        buildConfigField("String", "ENV_MODEL", quoted(envValue("SIFT_MODEL").second))
        buildConfigField("String", "ENV_API_KEY_NAME", quoted(keyEnv.first))
        buildConfigField("boolean", "DEMO", "false")
    }
    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = rootProject.file(signing.getValue("storeFile"))
            storePassword = signing.getValue("storePassword")
            keyAlias = signing.getValue("keyAlias")
            keyPassword = signing.getValue("keyPassword")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes {
        // The .env key is a convenience for your own debug installs only. Release APKs never contain it
        // unless you opt in with SIFT_EMBED_KEY_IN_RELEASE=true (never share such an APK).
        debug { buildConfigField("String", "ENV_API_KEY", quoted(keyEnv.second)) }
        release {
            val embed = envValue("SIFT_EMBED_KEY_IN_RELEASE").second == "true"
            buildConfigField("String", "ENV_API_KEY", quoted(if (embed) keyEnv.second else ""))
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
        // Made-up notifications for README screenshots; installs beside the real app and never reads the drawer.
        create("demo") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".demo"
            buildConfigField("String", "ENV_API_KEY", quoted(""))
            buildConfigField("boolean", "DEMO", "true")
            matchingFallbacks += "debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.preview)
    implementation(libs.compose.material)
    implementation(libs.compose.icons)
    debugImplementation(libs.compose.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.core)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore)
    implementation(libs.okhttp)
    implementation(libs.serialization)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.coroutines.test)
}
