package io.github.definitelystable.spongetube.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule

internal object BenchmarkToolchainContract {
    const val TARGET_PACKAGE = "io.github.definitelystable.spongetube"

    fun newRule(): MacrobenchmarkRule = MacrobenchmarkRule()

    fun correctnessCompilationMode(): CompilationMode = CompilationMode.None()

    val correctnessStartupMode: StartupMode = StartupMode.COLD
}
