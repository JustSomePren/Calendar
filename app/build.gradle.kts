plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlinx.kover")
}

android {
    namespace = "cz.calendar"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.calendar"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "2.3"
    }

    // The key is not in the repository. GitHub Actions gets it from secrets (see README),
    // without it the build uses this computer's own debug key.
    val keyFile = System.getenv("SIGNING_KEY_FILE")
    signingConfigs {
        if (keyFile != null) {
            create("own") {
                storeFile = file(keyFile)
                storePassword = System.getenv("SIGNING_KEY_PASSWORD")
                keyAlias = "release"
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            signingConfigs.findByName("own")?.let { signingConfig = it }
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
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // ScreenshotTour saves pictures of every screen into build/shots
        unitTests.all {
            it.systemProperty("roborazzi.test.record", "true")
            // Robolectric downloads older Android versions itself; -ProbolectricRepo=<url> points it to a mirror
            providers.gradleProperty("robolectricRepo").orNull?.let { url -> it.systemProperty("robolectric.dependency.repo.url", url) }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.work:work-testing:2.10.0")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
