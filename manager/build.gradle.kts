plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.compose.compiler) apply false
}

extra["androidMinSdkVersion"] = 31
extra["androidTargetSdkVersion"] = 37
extra["androidCompileSdkVersion"] = 37
extra["androidCompileSdkVersionMinor"] = 0
extra["androidBuildToolsVersion"] = "37.0.0"
extra["androidCompileNdkVersion"] = libs.versions.ndk.get()
extra["androidSourceCompatibility"] = JavaVersion.VERSION_21
extra["androidTargetCompatibility"] = JavaVersion.VERSION_21
extra["managerVersionCode"] = getVersionCode()
extra["managerVersionName"] = getVersionName()

fun gradleProp(name: String): String? =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }

fun getGitCommitCount(): Int {
    // PigeonSU: 本机 Gradle 子进程里 git 可能拿不到输出（PATH/LD_LIBRARY_PATH 不同），
    // 允许 -PPSU_VERSION_CODE 直接注入，并对空串兜底。
    gradleProp("PSU_VERSION_CODE")?.let { return it.toInt() - 30000 }
    val process = Runtime.getRuntime().exec(arrayOf("git", "rev-list", "--count", "HEAD"))
    return process.inputStream.bufferedReader().use { it.readText().trim() }.toIntOrNull() ?: 2707
}

fun getGitDescribe(): String {
    val process = Runtime.getRuntime().exec(arrayOf("git", "describe", "--tags", "--always"))
    return process.inputStream.bufferedReader().use { it.readText().trim() }
}

fun getVersionCode(): Int {
    val commitCount = getGitCommitCount()
    return 30000 + commitCount
}

fun getVersionName(): String {
    return gradleProp("PSU_VERSION_NAME") ?: getGitDescribe().ifBlank { "v3.3.1-custom" }
}
