import java.security.MessageDigest

pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "Clone Apps"
include(":app", ":core", ":clone-engine", ":ui", ":isolation-probe")
// Source-built compatibility experiment; the default build uses the first-party probe.
if (providers.gradleProperty("generalRuntime").orNull == "true") {
    val proof = file(".research/general-runtime-proof")
    val generatorHash = MessageDigest.getInstance("SHA-256")
        .digest(file("scripts/prepare-general-runtime-proof.py").readBytes())
        .joinToString("") { "%02x".format(it) }
    val stamp = proof.resolve("generator.sha256")
    check(proof.resolve("runtime/build.gradle").isFile && stamp.isFile && stamp.readText().trim() == generatorHash) {
        "Run python scripts/prepare-general-runtime-proof.py to regenerate the current experimental sources"
    }
    listOf("runtime", "reflection", "compiler", "free-reflection").forEach {
        include(":$it")
        project(":$it").projectDir = proof.resolve(it)
    }
}
