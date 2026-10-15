import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // Screenshot tests that render screens on the JVM: ./build.sh recordPaparazziDebug
    id("app.cash.paparazzi")
}

// The Play upload key lives outside the repo (see PLAY.md). Without it, release builds are signed with the
// debug key, which is fine for sideloading.
val uploadKeyProperties = file(System.getenv("POCKETSHELL_SIGNING") ?: "/nonexistent").takeIf { it.isFile }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "codes.exterkamp.whorl"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "codes.exterkamp.whorl"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.5.0"
        // The commit this was built from, set by build.sh; "+" means uncommitted changes.
        buildConfigField("String", "GIT_COMMIT", "\"${System.getenv("GIT_COMMIT") ?: "dev"}\"")
        ndk {
            // mosh-client is only built for arm64.
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (uploadKeyProperties != null) {
            create("upload") {
                storeFile = file(uploadKeyProperties.getProperty("storeFile"))
                storePassword = uploadKeyProperties.getProperty("storePassword")
                keyAlias = uploadKeyProperties.getProperty("keyAlias")
                keyPassword = uploadKeyProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    packaging {
        // libmoshclient.so is an executable; it has to be extracted to nativeLibraryDir to be exec'd.
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/versions/*/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":terminal-view"))

    implementation("com.github.mwiede:jsch:0.2.20")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation(platform("androidx.compose:compose-bom:2025.01.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub on the JVM; tests that parse JSON need the real one.
    testImplementation("org.json:json:20240303")
}
