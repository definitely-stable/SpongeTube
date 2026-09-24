plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.definitelystable.spongetube.testsupport.playback.f1"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
    }
}

dependencies {
    api(project(":core:engine"))
    api(project(":test-support:fixture-f1"))
}
