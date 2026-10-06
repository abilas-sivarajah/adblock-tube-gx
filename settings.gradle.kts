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
        // NewPipe Extractor (und dessen nanojson) gibt es nur über JitPack
        maven("https://jitpack.io")
    }
}

rootProject.name = "GXTube"
include(":app")
