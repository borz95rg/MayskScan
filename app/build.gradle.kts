plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Номер сборки на GitHub увеличивает версию, чтобы новые APK ставились поверх старых
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.maysk.scan"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.maysk.scan"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "SUPABASE_URL", "\"${findProperty("mayskscan.supabaseUrl") ?: ""}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${findProperty("mayskscan.supabaseKey") ?: ""}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Отдельный APK для 64-битных (почти все телефоны) и 32-битных телефонов — меньше размер
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    // Ключ подписи хранится только в секретах GitHub (KEYSTORE_BASE64 и KEYSTORE_PASSWORD).
    val keystorePath = System.getenv("MAYSK_KEYSTORE")
    val keystorePass = System.getenv("MAYSK_KEYSTORE_PASSWORD")
    val hasKey = !keystorePath.isNullOrEmpty() && !keystorePass.isNullOrEmpty() && file(keystorePath).exists()
    signingConfigs {
        if (hasKey) {
            create("shared") {
                storeFile = file(keystorePath!!)
                storeType = "jks"
                storePassword = keystorePass
                keyAlias = System.getenv("MAYSK_KEY_ALIAS")?.ifEmpty { null } ?: "mayskscan"
                keyPassword = System.getenv("MAYSK_KEY_PASSWORD")?.ifEmpty { null } ?: keystorePass
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (hasKey) signingConfigs.getByName("shared") else signingConfigs.getByName("debug")
        }
        getByName("debug") {
            if (hasKey) signingConfig = signingConfigs.getByName("shared")
        }
    }

    packaging {
        // Сжимать нативные библиотеки внутри APK (файл заметно меньше)
        jniLibs {
            useLegacyPackaging = true
            // OpenDocument.core and LiteRT-LM may both package the NDK C++ runtime.
            pickFirsts += "**/libc++_shared.so"
        }
        resources {
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Полноценный офлайн-движок просмотра и редактирования Office/ODF документов.
    implementation("app.opendocument:odr-core-android:7.6.1")
    // Локальная генеративная модель на устройстве (LiteRT-LM).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Сканер документов Google: границы, перспектива, тени (работает на телефоне)
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    // QR и штрихкоды — модель встроена в приложение
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // Определение языка — встроено; перевод — модели скачиваются один раз
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")
    // Камера для QR
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // Создание QR
    implementation("com.google.zxing:core:3.5.3")
    // Работа с PDF: объединение, разделение, сжатие, защита, подписи
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Распознавание текста на 8 языках без интернета
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.7.0")
}
