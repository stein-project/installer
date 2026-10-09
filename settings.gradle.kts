// The Stein Loader installer. It ships the Stein Bridge and the Stein Loader built from the repositories next to
// this one (../bridge and ../loader, or -Pstein.bridgeDir=... and -Pstein.loaderDir=...); the Loader's build brings
// the SDK (../sdk) along.
plugins {
    // JDK 25 for compiling: Gradle finds an installed one or downloads it.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "stein-installer"

for ((repo, default) in listOf("bridge" to "../bridge", "loader" to "../loader", "sdk" to "../sdk")) {
    val dir = file(providers.gradleProperty("stein.${repo}Dir").getOrElse(default))
    require(dir.resolve("settings.gradle.kts").isFile) {
        "the $repo repository was not found at $dir: clone it next to this one, or pass -Pstein.${repo}Dir=..."
    }
    includeBuild(dir) { name = "stein-$repo" }
}
