import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "at.mtr.logmygut"
    compileSdk = 37

    defaultConfig {
        applicationId = "at.mtr.logmygut"
        minSdk = 34
        targetSdk = 37
        // Raise versionCode for every APK given to others: Android only installs higher versions as updates.
        versionCode = 6
        versionName = "0.8.0-alpha"
    }

    // The release key lives outside the repository (SPEC.md, section 1: "Release builds").
    // Without it the release APK stays unsigned and can't be installed, so it never ships with a wrong key.
    val releaseKey = File(System.getProperty("user.home"), ".config/logmygut/keystore.properties")
    if (releaseKey.exists()) {
        val props = Properties().apply { releaseKey.inputStream().use(::load) }
        signingConfigs.create("release") {
            storeFile = File(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    // Lists the app's languages (English default, German) for Android's per-app language setting.
    androidResources {
        generateLocaleConfig = true
    }

    testOptions {
        // Robolectric needs merged resources and assets (seed files) on the unit-test classpath.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric touches FileDescriptor internals, which newer JDKs lock down.
            it.jvmArgs(
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
            )
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Migration tests read the exported Room schemas as assets. Robolectric only sees the merged debug
// assets (not test assets), so they go into debug builds; release builds don't contain them.
android.sourceSets.getByName("debug").assets.directories.add("$projectDir/schemas")

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    // Compose test pulls an Espresso too old for current Android; pin a newer one.
    testImplementation(libs.espresso.core)
}
