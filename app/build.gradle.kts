plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.melos"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.melos"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Classic Xposed API (de.robv.android.xposed.*), provided by the LSPosed/Vector
    // runtime at hook time — must NOT be packaged into the APK, hence compileOnly.
    compileOnly(files("libs/xposed-api-82.jar"))

    implementation(libs.androidx.core.ktx)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // The trajectory engine is pure Kotlin/JVM logic, unit-testable without a device.
    testImplementation(libs.junit)
    testImplementation(files("libs/xposed-api-82.jar"))
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.json:json:20231013")
}
