plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.definitelystable.spongetube"
    compileSdk = 36

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        applicationId = "io.github.definitelystable.spongetube"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.0-dev"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // F1 is bundled ONLY in the debug lab APK. M0 release/benchmark
        // source and byte measurement remain untouched.
        getByName("debug").assets.srcDir(
            rootProject.file("test-fixtures/media"),
        )
        getByName("benchmark").assets.srcDir(
            rootProject.file("test-fixtures/media"),
        )
    }

    lint {
        abortOnError = true
    }
}

dependencies {
    implementation(project(":playback:baseline"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.tracing)
    implementation(libs.androidx.compose.ui.tooling.preview)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(project(":test-support:playback-f1"))
    debugImplementation(libs.kotlinx.coroutines.core)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.kotlinx.coroutines.core)

    add("benchmarkImplementation", libs.androidx.profileinstaller)
    add("benchmarkImplementation", project(":playback:bridge"))
    add("benchmarkImplementation", project(":test-support:playback-f1"))
    add("benchmarkImplementation", libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
