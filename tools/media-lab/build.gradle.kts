plugins {
    application
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

application {
    mainClass = "io.github.definitelystable.spongetube.medialab.MediaLabMain"
    applicationDefaultJvmArgs = listOf("--add-modules=jdk.httpserver")
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.httpserver"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("--add-modules=jdk.httpserver")
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("--add-modules=jdk.httpserver")
}
