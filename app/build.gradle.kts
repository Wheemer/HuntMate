plugins {
    id("com.android.application")
}

val signingKeystorePath = providers.environmentVariable("HUNTMATE_KEYSTORE_FILE").orNull
val signingKeystore = signingKeystorePath?.let(::file)
val hasPersistentSigning = signingKeystore?.isFile == true &&
    providers.environmentVariable("HUNTMATE_KEYSTORE_PASSWORD").isPresent &&
    providers.environmentVariable("HUNTMATE_KEY_ALIAS").isPresent &&
    providers.environmentVariable("HUNTMATE_KEY_PASSWORD").isPresent

android {
    namespace = "com.huntmate.gps"
    compileSdk = 37

    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "com.huntmate.gps"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.5.0"
        val outdoorDownloadUrl = providers.gradleProperty("huntmateOutdoorDownloadUrl").orNull ?: ""
        buildConfigField("String", "OUTDOOR_DOWNLOAD_URL", "\"$outdoorDownloadUrl\"")
    }

    signingConfigs {
        if (hasPersistentSigning) {
            create("persistent") {
                storeFile = requireNotNull(signingKeystore)
                storePassword = providers.environmentVariable("HUNTMATE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("HUNTMATE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("HUNTMATE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasPersistentSigning) {
                signingConfig = signingConfigs.getByName("persistent")
            }
        }
        release {
            isMinifyEnabled = false
            if (hasPersistentSigning) {
                signingConfig = signingConfigs.getByName("persistent")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.maplibre.gl:android-sdk:13.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
