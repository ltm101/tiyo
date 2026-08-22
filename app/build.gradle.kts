plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.koyo.screenwarden"
    compileSdk = 35
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "app.tiyo.opensource"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "1.3.0"

        buildConfigField("String", "TIYO_ARK_API_KEY", "\"\"")
        buildConfigField("String", "FLAVOR", "\"public\"")
        buildConfigField("String", "TIYO_ARK_MODEL", "\"\"")
        buildConfigField("String", "TIYO_AGNES_API_KEY", "\"\"")
        buildConfigField("String", "TIYO_GLM_API_KEY", "\"\"")
        buildConfigField("String", "TIYO_DEFAULT_MODEL", "\"deepseek-v4-flash\"")
        buildConfigField("String", "TIYO_PRESET_DEEPSEEK_KEY", "\"\"")
        buildConfigField("String", "TIYO_PRESET_MINIMAX_KEY", "\"\"")
        buildConfigField("String", "TIYO_PRESET_IMAGE_KEY", "\"\"")
        buildConfigField("String", "TIYO_PRESET_DEEPSEEK_BASE_URL", "\"https://api.deepseek.com/v1\"")
        buildConfigField("String", "TIYO_PRESET_DEEPSEEK_MODEL", "\"deepseek-v4-flash\"")
        buildConfigField("String", "TIYO_PRESET_IMAGE_BASE_URL", "\"\"")
        buildConfigField("String", "TIYO_PRESET_IMAGE_MODEL", "\"\"")

    }

    buildFeatures {
        buildConfig = true
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

    lint {
        disable += setOf("FragmentLiveDataObserve", "FragmentBackPressedCallback")
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
    implementation("com.larksuite.oapi:oapi-sdk:2.7.3") {
        exclude(group = "org.robolectric", module = "android-all")
        exclude(group = "org.conscrypt", module = "conscrypt-openjdk-uber")
    }
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
