plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.koyo.screenwarden"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.tiyo.opensource"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "5.2.0"

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Public release artifacts are intentionally unsigned.
            // Maintainers provide their own signing configuration outside Git.
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/NOTICE.md",
                "META-INF/LICENSE.md",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].assets.srcDir(rootProject.file("third_party/licenses"))
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("com.sun.mail:jakarta.mail:2.0.1")
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
