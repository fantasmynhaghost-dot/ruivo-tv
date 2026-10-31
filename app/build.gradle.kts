plugins { id("com.android.application") }
android {
    namespace = "br.sintonia.tv"
    compileSdk = 34
    defaultConfig {
        applicationId = "br.sintonia.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation(project(":playback-contract")) }
