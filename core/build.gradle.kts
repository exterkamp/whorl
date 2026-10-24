// Connection logic shared by the Android and desktop apps: machine profiles, reaching a machine over its
// addresses, SSH, the mosh-server handshake, and herdr. Platform-specific pieces come in through [Platform].
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":terminal-core"))
    api("com.github.mwiede:jsch:0.2.20")
    api("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // Android ships org.json in the platform; the desktop app adds the library itself.
    compileOnly("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
