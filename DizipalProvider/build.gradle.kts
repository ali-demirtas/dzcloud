plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 10

cloudstream {
    setRepo("https://github.com/ali-demirtas/dzcloud")
    authors = listOf("ali-demirtas")
    description = "Dizipal film ve dizi sağlayıcısı"
    status = 1
    language = "tr"
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://dizipal2302.com/uploads/site/favicon-192x192.png"
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
        freeCompilerArgs = listOf("-Xskip-metadata-version-check")
    }
}

dependencies {
    compileOnly("com.github.recloudstream:cloudstream:master-SNAPSHOT")
    compileOnly("com.github.Blatzar:NiceHttp:0.4.18")
    implementation("org.jsoup:jsoup:1.17.2")
}
