plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val numero = (System.getenv("VERSION_CODE") ?: "1").toInt()
val firma = System.getenv("KEYSTORE_FILE")

android {
    namespace = "com.locometopa.mandoservidor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.locometopa.mandoservidor"
        minSdk = 26
        targetSdk = 34
        versionCode = numero
        versionName = "1.$numero"
    }

    signingConfigs {
        create("release") {
            if (firma != null) {
                storeFile = file(firma)
                storePassword = System.getenv("KEYSTORE_PASSWORD")?.trim()
                keyAlias = "mando"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")?.trim()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (firma != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources { excludes += setOf("META-INF/versions/**", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/LICENSE*", "META-INF/NOTICE*") }
    }
}

dependencies {
    implementation("com.github.mwiede:jsch:0.2.21")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
}
