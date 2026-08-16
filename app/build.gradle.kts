import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * リリース署名の設定。
 *
 * このリポジトリは公開なので、鍵もパスワードもリポジトリの外
 * (`C:\Users\hai\skimas\keystore\`) に置いてある。
 *
 * **鍵かパスワードを失うと、配布済みアプリへ二度と更新を配れない。**
 * Android は別の鍵で署名されたAPKの上書きインストールを拒否するので、
 * 利用者は一度アンインストールするしかなくなる。両方を必ず別の場所へ控えること。
 *
 * 見つからない場合は release ビルドを未署名にする（デバッグ鍵で署名して
 * 「リリース版のつもり」の物が出回るのが一番まずい）。
 */
val keystorePropsFile = rootProject.file("../keystore/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile")?.let { file(it).exists() } == true
if (!hasReleaseKey) {
    // 黙って未署名APKが出ると「リリース版を作ったつもり」で配ってしまう。必ず気付かせる。
    logger.warn("警告: リリース署名鍵が見つかりません ($keystorePropsFile)。release ビルドは未署名になります。")
}

android {
    namespace = "dev.togar.dynasched"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.togar.dynasched"
        minSdk = 26
        targetSdk = 34
        versionCode = 25
        versionName = "25.0"
        // バックエンドのベースURL。変えたい場合はここだけ書き換える。
        buildConfigField("String", "API_BASE_URL", "\"https://api.togar.dev\"")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    // テストのみ。APKには入らないので「外部ライブラリ不使用」の方針とは衝突しない
    testImplementation("junit:junit:4.13.2")
}
