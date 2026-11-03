import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Cheia de upload pentru Google Play; fișierul nu intră în git
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "ro.awebdesign.brickpilot"
    compileSdk = 36

    defaultConfig {
        applicationId = "ro.awebdesign.brickpilot"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.5.0"

        // OpenCV are biblioteci native pentru fiecare procesor; telefoanele reale sunt ARM
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("upload") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    // Log și celelalte clase Android întorc valori implicite în testele de pe calculator
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val cameraxVersion = "1.4.2"

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    // Viziunea: prag adaptiv, perspectivă, contururi, CamShift
    implementation("org.opencv:opencv:4.12.0")

    testImplementation("junit:junit:4.13.2")
    // OpenCV pentru calculator, ca testele să ruleze aceeași viziune pe imagini sintetice
    testImplementation("org.openpnp:opencv:4.9.0-0")
}
