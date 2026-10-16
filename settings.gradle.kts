pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "Clone Apps"
include(":app", ":core", ":clone-engine", ":ui", ":isolation-probe")
// Source-built compatibility experiment; deliberately separate from released APKs.
if (providers.gradleProperty("generalRuntime").orNull == "true") {
    val proof = file(".research/general-runtime-proof")
    check(proof.resolve("runtime/build.gradle").isFile) {
        "Run python scripts/prepare-general-runtime-proof.py before -PgeneralRuntime=true"
    }
    listOf("runtime", "reflection", "compiler", "free-reflection").forEach {
        include(":$it")
        project(":$it").projectDir = proof.resolve(it)
    }
}
