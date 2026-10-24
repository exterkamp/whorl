plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.terminal"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "26.3.11579264"

    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            ndkBuild {
                cFlags("-std=c11", "-Wall", "-Wextra", "-Werror", "-Os", "-fno-stack-protector", "-Wl,--gc-sections")
            }
        }
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The emulator proper; this module adds the Android session, pty and JNI around it.
    api(project(":terminal-core"))
}
