// Android Auto receiver. AGPL-3.0-or-later: the protocol handling is ported from Headunit
// (Michael Reid), anod/headunit and open-headunit; see docs/ANDROID_AUTO.md.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.shilapi.xcertplay.androidauto"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":shared"))
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
