plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.visualautomation"
    compileSdk = 35
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.example.visualautomation"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.3.0"

        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DOpenCV_DIR=${project.findProperty("OpenCV_DIR") ?: ""}"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // OpenCV_DIR = <sdk>/native/jni  ->  prebuilt .so files live in <sdk>/native/libs/<abi>
    val openCvDir = project.findProperty("OpenCV_DIR") as String?
    if (!openCvDir.isNullOrBlank()) {
        sourceSets.getByName("main").jniLibs.srcDir(File(openCvDir).parentFile.resolve("libs"))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
