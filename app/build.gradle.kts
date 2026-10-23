plugins {
    id("com.android.application")
}

android {
    namespace = "com.hyperconnect.app"
    compileSdk = 36
    useLibrary("android.car") // PERF_VEHICLE_SPEED 조회

    defaultConfig {
        applicationId = "com.hyperconnect.app"
        minSdk = 28 // Pleos Connect 요구사항
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
}
