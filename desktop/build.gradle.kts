import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Whorl for Windows (and, for development, Linux): Compose Desktop over the shared :core.
//   ./build.sh :desktop:run           run it (needs a display; see desktop/README.md)
//   ./build.sh :desktop:test          unit tests, including headless screenshots
//   gradle :desktop:packageMsi        the Windows installer; only works on Windows (CI does it)
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(17)
}

/** The MSI's version must be numeric (a.b.c); keep it in step with the Android app's versionName. */
val whorlVersion = "0.5.2"

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.json:json:20240303")
    // A pty (ConPTY on Windows) for mosh-client, which needs a real terminal. Brings JNA, also used for DPAPI.
    implementation("org.jetbrains.pty4j:pty4j:0.13.13")
    // DPAPI for the settings file; pty4j uses the same version.
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation(compose.desktop.uiTestJUnit4)
}

compose.desktop {
    application {
        mainClass = "codes.exterkamp.whorl.desktop.MainKt"
        jvmArgs += listOf("-Dwhorl.version=$whorlVersion", "-Dwhorl.commit=${System.getenv("GIT_COMMIT") ?: "dev"}")

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Whorl"
            packageVersion = whorlVersion
            description = "SSH and mosh client"
            vendor = "exterkamp"
            copyright = "Shane Exterkamp"
            // bundled/windows/mosh/: Cygwin's mosh-client.exe, its DLLs and terminfo, put there by CI
            // (.github/workflows/windows.yml). Compose ships bundled/<os>/ for the OS being packaged.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("bundled"))
            modules("java.naming", "jdk.crypto.ec", "jdk.unsupported")
            windows {
                iconFile.set(project.file("icons/whorl.ico"))
                menuGroup = "Whorl"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Never change: it's how Windows knows a new MSI upgrades the installed one.
                upgradeUuid = "6f4f5a9e-2a0c-4f7e-9a7b-5c1d2e3f4a5b"
            }
            linux {
                iconFile.set(project.file("icons/whorl.png"))
            }
        }
    }
}
