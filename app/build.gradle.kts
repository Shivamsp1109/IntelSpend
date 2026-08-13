plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt)
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

/**
 * API endpoints, per build type.
 *
 * Set `spendwise.api.debug` / `spendwise.api.release` in gradle.properties (or
 * pass `-Pspendwise.api.release=...`) rather than editing this file, so a
 * developer's LAN address never lands in version control or in a release.
 *
 * The release URL is checked for TLS at configuration time. A release build is
 * also covered at runtime by the network security config, which forbids
 * cleartext outright — this check just turns a typo into a build failure rather
 * than a request that dies on the user's phone.
 */
val debugApiBaseUrl: String =
    (findProperty("spendwise.api.debug") as String?) ?: "http://192.168.1.19:3000/"

val releaseApiBaseUrl: String =
    (findProperty("spendwise.api.release") as String?) ?: "https://api.spendwise.invalid/"

require(releaseApiBaseUrl.startsWith("https://")) {
    "spendwise.api.release must be an https:// URL, but was '$releaseApiBaseUrl'. " +
        "Release builds refuse cleartext traffic, so an http:// endpoint cannot work."
}

/**
 * Certificate pins for the API host, as `sha256/<base64>` values.
 *
 * Set `spendwise.api.pins` to a comma-separated list. Empty means no pinning,
 * which is the right default here: a pin that does not match the server's real
 * certificate chain does not degrade the connection, it refuses it, and an app
 * that cannot reach its backend is bricked until the next release.
 *
 * Supply at least two — the pin currently serving traffic, and a backup for the
 * key you will rotate to. With a single pin, the day the certificate is renewed
 * is the day every installed copy of the app stops working.
 */
val apiCertificatePins: String =
    (findProperty("spendwise.api.pins") as String?)?.trim().orEmpty()

require(apiCertificatePins.isEmpty() || apiCertificatePins.split(",").size >= 2) {
    "spendwise.api.pins needs at least two pins (current plus a rotation backup), " +
        "or none at all. A lone pin turns certificate renewal into an outage."
}

android {
    namespace = "com.spendwise"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spendwise"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("String", "MYSQL_API_BASE_URL", "\"$debugApiBaseUrl\"")
            // Never pinned in debug: a local server uses a self-signed
            // certificate, and pinning would simply block development.
            buildConfigField("String", "API_CERTIFICATE_PINS", "\"\"")
        }
        release {
            buildConfigField("String", "MYSQL_API_BASE_URL", "\"$releaseApiBaseUrl\"")
            buildConfigField("String", "API_CERTIFICATE_PINS", "\"$apiCertificatePins\"")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.coil.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    // Declared directly rather than leant on transitively: CertificatePinner and
    // HttpUrl are used in this module's own code.
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Ingestion pipeline
    implementation(libs.mlkit.text.recognition)
    implementation(libs.opencsv)
    implementation(libs.pdfbox.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    // Encrypts the database at rest. Room talks to it through the standard
    // SupportSQLite interfaces, so no DAO or query changes are needed.
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.storage)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
