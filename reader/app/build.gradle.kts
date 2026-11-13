plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "local.bilingual.reader"
    lint {
        // The AnkiDroid API library ships lint checks written for AnkiDroid's own code (its collection clock).
        disable += "DirectSystemCurrentTimeMillisUsage"
    }
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "local.bilingual.reader"
        // Built for one device: OPPO Reno5 (CPH2159) on ColorOS 13 = Android 13, API 33.
        minSdk = 33
        targetSdk = 36
        versionCode = 7
        versionName = "0.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Personal sideloaded build: the debug key is enough.
            signingConfig = signingConfigs.getByName("debug")
        }
        // Release-like, non-debuggable build that instrumented timing tests can attach to (./gradlew -Pperf ...).
        create("perf") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    testBuildType = if (project.hasProperty("perf")) "perf" else "debug"

    buildFeatures {
        compose = true
    }

    // Test fixtures used by both the JVM and the instrumented tests (TestBook).
    sourceSets {
        getByName("test").kotlin.directories += "src/sharedTest/java"
        getByName("androidTest").kotlin.directories += "src/sharedTest/java"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("com.github.ankidroid:Anki-Android:api-v1.1.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    // Package format check against ../format/*.schema.json, the same files the builder and the web reader use.
    testImplementation("com.networknt:json-schema-validator:1.5.9")

    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    "perfImplementation"("androidx.compose.ui:ui-test-manifest")
}
