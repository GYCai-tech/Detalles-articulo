plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "es.gyc.detalles"
    compileSdk = 36

    defaultConfig {
        applicationId = "es.gyc.detalles"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                          "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }

    buildFeatures { compose = true }
}

dependencies {
    val compose = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(compose)
    androidTestImplementation(compose)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Ajustes persistentes: URL del servidor y usuario ERP.
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // OkHttp directo en vez de Retrofit: son cuatro llamadas y una subida
    // multipart. Menos capas que versionar y menos que pueda romperse.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Escaner de codigos de Google Play. Evita montar una tuberia de CameraX
    // y no necesita permiso de camara propio.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // La foto se saca con la camara del sistema via FileProvider.
    implementation("androidx.core:core-ktx:1.15.0")
}
