plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    setRepo("https://github.com/ali-demirtas/dzcloud")
    authors = listOf("ali-demirtas")
}

android {
    namespace = "com.dizipal"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // pre-release yerine -SNAPSHOT kullanıyoruz
    val cloudstreamApi = "-SNAPSHOT"
    implementation("com.github.recloudstream:cloudstream:$cloudstreamApi")
}
