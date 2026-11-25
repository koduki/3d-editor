plugins { id("com.android.application") version "9.5.1" }
android {
    namespace = "dev.koduki.kucode.probe"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.koduki.kucode.probe"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "1"
    }
}
dependencies { testImplementation("junit:junit:4.13.2") }
