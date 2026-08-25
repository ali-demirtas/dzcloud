plugins {
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
