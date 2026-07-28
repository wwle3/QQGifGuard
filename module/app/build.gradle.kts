plugins {
    id("com.android.application")
}

android {
    namespace = "com.pjz.qqgifguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pjz.qqgifguard"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
