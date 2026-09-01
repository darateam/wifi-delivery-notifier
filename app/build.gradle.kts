plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "co.hy.wifidelivery"
    compileSdk = 35

    defaultConfig {
        applicationId = "co.hy.wifidelivery"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"

        // 수집 엔드포인트. 비워두면 텔레메트리는 로컬 큐에만 쌓이고 전송하지 않는다.
        // 실제 값은 로컬 gradle.properties 또는 CI 시크릿으로 주입한다.
        buildConfigField("String", "INGEST_ENDPOINT",
            "\"${project.findProperty("ingestEndpoint") ?: ""}\"")
        buildConfigField("String", "INGEST_API_KEY",
            "\"${project.findProperty("ingestApiKey") ?: ""}\"")
        // BSSID 해시 솔트. 반드시 교체하고 리포에 커밋하지 말 것.
        buildConfigField("String", "BSSID_SALT",
            "\"${project.findProperty("bssidSalt") ?: "CHANGE_ME"}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
}
