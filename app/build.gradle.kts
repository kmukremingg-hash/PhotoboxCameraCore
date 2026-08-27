plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kevo.photoboxcamera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kevo.photoboxcamera"
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "0.7"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val generatePhotoboxIcon by tasks.registering {
    doLast {
        val source = file("icon.b64")
        val output = file("src/main/res/drawable/photobox_icon.webp")
        output.parentFile.mkdirs()
        output.writeBytes(java.util.Base64.getDecoder().decode(source.readText().trim()))
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(generatePhotoboxIcon)
}
