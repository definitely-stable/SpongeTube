plugins {
    alias(libs.plugins.android.library)
}

// Test-only support module: the single F1 FetchUnit catalog shared by M1-C
// seeds and M1-E PlaybackBridge scenarios. It must only be consumed from
// test/androidTest source sets, never from product code.
android {
    namespace = "io.github.definitelystable.spongetube.testsupport.fixture.f1"
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
    api(project(":core:storage"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
