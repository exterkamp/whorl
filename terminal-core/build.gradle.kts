// The terminal emulator itself, in plain Java so the Android app and the desktop app both use it.
// Android-only pieces (TerminalSession's main-thread handler, the local pty) stay in :terminal-emulator.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
