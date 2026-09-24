plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.definitelystable.spongetube.playback.bridge"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
    }

    sourceSets {
        getByName("androidTest").assets.directories.add(
            rootProject.file("test-fixtures/media").absolutePath,
        )
    }
}

dependencies {
    api(project(":core:engine"))
    api(libs.androidx.media3.common)
    api(libs.androidx.media3.datasource)
    api(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.dash)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(project(":test-support:fixture-f1"))
    androidTestImplementation(project(":test-support:playback-f1"))
    androidTestImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
