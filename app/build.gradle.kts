import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mallner.triplogger"
    compileSdk {
        version = release(36)
    }

        // Release signing, secret-free at rest: keystore.properties (git-ignored)
    // stores only the NON-secret pointers (storeFile, keyAlias). The passwords
    // are supplied per-invocation via TRIPLOGGER_STORE_PASSWORD /
    // TRIPLOGGER_KEY_PASSWORD;
    // without them assembleRelease produces an unsigned APK on purpose so
    // anyone can build and sign with their own key.
    val keystoreProperties = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val releaseStorePassword = System.getenv("TRIPLOGGER_STORE_PASSWORD").orEmpty()
    val signingAvailable = keystoreProperties.getProperty("storeFile") != null && releaseStorePassword.isNotEmpty()
    if (signingAvailable) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = releaseStorePassword
                keyAlias = keystoreProperties.getProperty("keyAlias", "triplogger")
                keyPassword = System.getenv("TRIPLOGGER_KEY_PASSWORD").orEmpty()
                    .ifEmpty { releaseStorePassword } // RETURN-at-generation alias
            }
        }
    }

    defaultConfig {
        applicationId = "com.mallner.triplogger"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.play.services.location)
    implementation(libs.datastore.preferences)
    implementation(libs.documentfile)
    testImplementation(libs.junit)
    // Real org.json for unit tests (the mockable android.jar only stubs it)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}