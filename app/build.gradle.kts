plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "de.timpara.karoosweat"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.timpara.karoosweat"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 26
        targetSdk = 35
        // CI supplies a monotonic build number; local builds stay at 1.
        versionCode = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionName = System.getenv("RELEASE_VERSION")?.removePrefix("v") ?: "0.1.0"
    }

    // Resolve the signing material from one of two places, in order:
    //   1. CI: the KEYSTORE_PATH / *_PASSWORD / KEY_ALIAS environment variables,
    //      populated from repository secrets.
    //   2. Local: ~/.config/karoo-sweat/, so the maintainer can produce a signed
    //      release build off a personal machine without CI. Password is read from a
    //      sibling file rather than hardcoded.
    // If neither is present the release build is left unsigned, so contributors and
    // forks can still build it. The keystore itself is never committed.
    val signing: Triple<File, String, String>? = run {
        val envPath = System.getenv("KEYSTORE_PATH")
        if (envPath != null && file(envPath).exists()) {
            val pw = System.getenv("KEYSTORE_PASSWORD") ?: ""
            return@run Triple(file(envPath), pw, System.getenv("KEY_ALIAS") ?: "karoo-sweat")
        }
        val local = File(System.getProperty("user.home"), ".config/karoo-sweat/karoo-sweat.jks")
        val pwFile = File(System.getProperty("user.home"), ".config/karoo-sweat/keystore-password.txt")
        if (local.exists() && pwFile.exists()) {
            return@run Triple(local, pwFile.readText().trim(), "karoo-sweat")
        }
        null
    }

    signingConfigs {
        create("release") {
            signing?.let { (store, password, alias) ->
                storeFile = store
                storePassword = password
                keyAlias = alias
                keyPassword = System.getenv("KEY_PASSWORD") ?: password
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signing != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // compilerOptions rather than the deprecated kotlinOptions string DSL, which
    // Kotlin 2.4 removes outright.
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":model"))

    implementation(libs.hammerhead.karoo.ext)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.bundles.androidx.lifeycle)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.compose.ui)
    implementation(libs.androidx.glance.appwidget)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.junit)
}
