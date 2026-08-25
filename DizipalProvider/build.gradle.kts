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
        freeCompilerArgs = listOf("-Xskip-metadata-version-check")
    }
}

dependencies {
    val cloudstreamApi = "-SNAPSHOT"
    implementation("com.github.recloudstream:cloudstream:$cloudstreamApi")
    implementation("org.jsoup:jsoup:1.17.2")
}
