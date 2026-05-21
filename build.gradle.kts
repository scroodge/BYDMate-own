plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
    id("com.google.dagger.hilt.android") version "2.53.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}

tasks.register<Exec>("releaseChangelog") {
    group = "release"
    description = "Generate a Keep a Changelog release section from git commits."

    val releaseVersion = providers.gradleProperty("releaseVersion").orElse("auto")
    val fromTag = providers.gradleProperty("fromTag")
    val dryRun = providers.gradleProperty("dryRun")
        .map { it.equals("true", ignoreCase = true) }
        .orElse(false)

    doFirst {
        commandLine(
            listOf("node", "release/generate-changelog.js", "--version", releaseVersion.get()) +
                fromTag.map { listOf("--from-tag", it) }.orElse(emptyList()).get() +
                if (dryRun.get()) listOf("--dry-run") else emptyList()
        )
    }
}
