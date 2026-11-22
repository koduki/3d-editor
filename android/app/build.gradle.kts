plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val generatedWebAssetsRoot = layout.buildDirectory.dir("generated/web-assets")

val syncWebAssets by tasks.registering(Sync::class) {
    from(rootProject.file("../public"))
    into(generatedWebAssetsRoot.map { it.dir("www") })
}

android {
    namespace = "dev.koduki.xracp"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.koduki.xracpcompanion"
        minSdk = 34
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        val ksPath = System.getenv("ANDROID_KEYSTORE_PATH")
        if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(generatedWebAssetsRoot)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.named("preBuild") {
    dependsOn(syncWebAssets)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Jetpack XR SDK (stable 1.0.0 runtime & SceneCore for Android XR spatial integration)
    implementation("androidx.xr.runtime:runtime:1.0.0")
    implementation("androidx.xr.scenecore:scenecore:1.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
