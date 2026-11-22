plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.koduki.xracp"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.koduki.xracpcompanion"
        minSdk = 34
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Jetpack XR SDK (stable 1.0.0 runtime & SceneCore for Android XR spatial integration)
    implementation("androidx.xr.runtime:runtime:1.0.0")
    implementation("androidx.xr.scenecore:scenecore:1.0.0")

    testImplementation("junit:junit:4.13.2")
}

// Keep android assets in single-source sync with ../../public on every Gradle build
val syncWebAssets by tasks.registering(Sync::class) {
    from(rootProject.file("../public"))
    into(layout.projectDirectory.dir("src/main/assets/www"))
}

tasks.named("preBuild") {
    dependsOn(syncWebAssets)
}
