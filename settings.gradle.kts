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
    }
}
rootProject.name = "whorl"
// The desktop app and what it shares. WHORL_DESKTOP_ONLY leaves the Android modules out, for machines without
// the Android SDK (the Windows CI runner).
include(":core", ":desktop", ":terminal-core")
if (System.getenv("WHORL_DESKTOP_ONLY") == null) include(":app", ":terminal-emulator", ":terminal-view")
