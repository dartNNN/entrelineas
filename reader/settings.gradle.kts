pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // AnkiDroid's add-content API is published only on JitPack; nothing else is taken from there.
        maven("https://jitpack.io") { content { includeGroup("com.github.ankidroid") } }
    }
}
rootProject.name = "BilingualReader"
include(":app")
