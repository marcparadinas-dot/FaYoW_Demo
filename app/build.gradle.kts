

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.android.libraries.mapsplatform.secrets.gradle.plugin)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.example.fayowdemo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.fayowdemo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"  // Ajouté
    }
}

dependencies {

        // Bibliothèque de base
        implementation("com.mikepenz:iconics-core:5.3.3")

        // Exemple : Pack Material Design
        implementation("com.mikepenz:google-material-typeface:4.0.0.2-kotlin@aar")

        // Exemple : Pack Font Awesome
        implementation("com.mikepenz:fontawesome-typeface:5.9.0.2-kotlin@aar")


    implementation("com.google.android.gms:play-services-location:21.0.1") // Pour la localisation
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Google Maps
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    //implementation("com.google.android.gms:play-services-maps:19.2.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Firebase (BoM + librairies)
    //implementation(platform("com.google.firebase:firebase-bom:34.7.0"))  // Version en dur
    //implementation("com.google.firebase:firebase-auth-ktx")
    //implementation("com.google.firebase:firebase-firestore-ktx")
    //implementation("com.google.firebase:firebase-storage-ktx")

    // Nouveau bloc (avec versions explicites)
    implementation("com.google.firebase:firebase-auth-ktx:22.3.1")
    implementation("com.google.firebase:firebase-firestore-ktx:24.10.3")
    implementation("com.google.firebase:firebase-storage-ktx:20.3.0")


}