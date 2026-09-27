import com.android.build.gradle.internal.tasks.factory.dependsOn
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsPlugin
import com.google.gms.googleservices.GoogleServicesPlugin
import java.io.BufferedReader
import java.io.FileInputStream
import java.security.SecureRandom
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kapt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.navigation)
}

val packageName = "com.hitec.agentpro"
val useDifferentPackageNameForDebugBuild = false

val sdkPath = providers.gradleProperty("LinphoneSdkBuildDir").get()
val googleServices = File(projectDir.absolutePath + "/google-services.json")
val linphoneLibs = File("$sdkPath/libs/")
val linphoneDebugLibs = File("$sdkPath/libs-debug/")
val firebaseCloudMessagingAvailable = googleServices.exists()
val crashlyticsAvailable = googleServices.exists() && linphoneLibs.exists() && linphoneDebugLibs.exists()

if (firebaseCloudMessagingAvailable) {
    println("google-services.json found, enabling Firebase CloudMessaging feature")
    apply<GoogleServicesPlugin>()
} else {
    println("google-services.json not found, disabling Firebase CloudMessaging feature")
}
if (crashlyticsAvailable) {
    println("google-services.json found and Linphone SDK libs-debug folder found, enabling Crashlytics feature")
    apply<CrashlyticsPlugin>()
} else {
    println("Crashlytics has been disabled because either google-services.json file wasn't found or local Linphone SDK build folder isn't configured")
}

var gitVersion = "6.1.0-alpha"
var gitBranch = ""
try {
    val gitDescribe = ProcessBuilder()
        .command("git", "describe", "--abbrev=0")
        .directory(project.rootDir)
        .start()
        .inputStream.bufferedReader().use(BufferedReader::readText)
        .trim()
    println("Git describe: $gitDescribe")

    val gitCommitsCount = ProcessBuilder()
        .command("git", "rev-list", "$gitDescribe..HEAD", "--count")
        .directory(project.rootDir)
        .start()
        .inputStream.bufferedReader().use(BufferedReader::readText)
        .trim()
    println("Git commits count: $gitCommitsCount")

    val gitCommitHash = ProcessBuilder()
        .command("git", "rev-parse", "--short", "HEAD")
        .directory(project.rootDir)
        .start()
        .inputStream.bufferedReader().use(BufferedReader::readText)
        .trim()
    println("Git commit hash: $gitCommitHash")

    gitBranch = ProcessBuilder()
        .command("git", "name-rev", "--name-only", "HEAD")
        .directory(project.rootDir)
        .start()
        .inputStream.bufferedReader().use(BufferedReader::readText)
        .trim()
    println("Git branch name: $gitBranch")

    gitVersion =
        if (gitCommitsCount.toInt() == 0) {
            gitDescribe
        } else {
            "$gitDescribe.$gitCommitsCount+$gitCommitHash"
        }
} catch (e: Exception) {
    println("Git not found [$e], using $gitVersion")
}
println("Computed git version: $gitVersion")

// AgentPro: the SecretCipher key is not part of the source code. It lives in
// credential-key.properties at the project root, which is gitignored and never published — the
// credentials the APK Builder embeds in customer builds are encrypted with it, and so are the PBX
// passwords saved on the device. A checkout without that file gets a fresh random key written
// there, so later builds on that machine keep using the same one: a device's saved passwords only
// decrypt with the key of the build that saved them. The builder reads this same file.
val credentialKeyFile = rootProject.file("credential-key.properties")
if (!credentialKeyFile.exists()) {
    val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    credentialKeyFile.writeText(
        "# AgentPro credential key (AES-256, hex). Private: never commit or publish this file.\n" +
            "# Back it up with the signing key. A build made with another key cannot read the PBX\n" +
            "# passwords that builds made with this one saved on a device.\n" +
            "key=$fresh\n"
    )
    println("Generated a new credential key in ${credentialKeyFile.name} - back it up with the signing key")
}
val credentialKeyHex = Properties().apply {
    FileInputStream(credentialKeyFile).use { load(it) }
}.getProperty("key").orEmpty().trim().lowercase()
require(Regex("[0-9a-f]{64}").matches(credentialKeyHex)) {
    "${credentialKeyFile.absolutePath} must hold key=<64 hex characters>"
}

// Emitted as source while Gradle configures, so it exists before any task runs, and outside
// app/build so the builder's `:app:clean` cannot delete it between configuration and compilation.
// XOR-masked so the key is not a plain literal in the DEX — obfuscation only, see SecretCipher.
val credentialKeySourceDir = rootProject.layout.buildDirectory
    .dir("generated/agentpro/credential-key").get().asFile
run {
    val masked = credentialKeyHex.chunked(2).joinToString("") { "%02x".format(it.toInt(16) xor 0x5A) }
    val text = "// Generated by app/build.gradle.kts from credential-key.properties. Do not edit or commit.\n" +
        "package org.linphone.utils\n\n" +
        "internal object CredentialKey {\n" +
        "    const val MASK = 0x5A\n" +
        "    const val MASKED_HEX = \"$masked\"\n" +
        "}\n"
    val file = File(credentialKeySourceDir, "org/linphone/utils/CredentialKey.kt")
    if (!file.exists() || file.readText() != text) {
        file.parentFile.mkdirs()
        file.writeText(text)
    }
}

configurations {
    implementation { isCanBeResolved = true }
}

tasks.register("linphoneSdkSource") {
    doLast {
        configurations.implementation.get().incoming.resolutionResult.allComponents.forEach {
            if (it.id.displayName.contains("linphone-sdk-android")) {
                println("Linphone SDK used is ${it.moduleVersion?.version}")
            }
        }
    }
}
project.tasks.preBuild.dependsOn("linphoneSdkSource")

android {
    // namespace controls where R class is generated - must match source code imports
    namespace = "org.linphone"
    compileSdk = 36

    defaultConfig {
        applicationId = packageName
        minSdk = 28
        targetSdk = 36
        versionCode = 100001 // 1.00.001
        versionName = "1.0.0"

        manifestPlaceholders["appAuthRedirectScheme"] = packageName

        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                output.outputFileName = "proagent-${variant.buildType.name}-$gitVersion.apk"
            }
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))

    signingConfigs {
        create("release") {
            val keyStorePath = keystoreProperties["storeFile"] as String
            val keyStore = project.file(keyStorePath)
            if (keyStore.exists()) {
                storeFile = keyStore
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                println("Signing config release is using keystore [$storeFile]")
            } else {
                println("Keystore [$storeFile] doesn't exists!")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (useDifferentPackageNameForDebugBuild) {
                applicationIdSuffix = ".debug"
            }
            isDebuggable = true
            isJniDebuggable = true

            val appVersion = gitVersion
            val appBranch = gitBranch
            println("Debug flavor app version is [$appVersion], app branch is [$appBranch]")
            resValue("string", "linphone_app_version", appVersion)
            resValue("string", "linphone_app_branch", appBranch)
            if (useDifferentPackageNameForDebugBuild) {
                resValue("string", "file_provider", "$packageName.debug.fileprovider")
            } else {
                resValue("string", "file_provider", "$packageName.fileprovider")
            }
            resValue("string", "linphone_openid_callback_scheme", packageName)

            if (crashlyticsAvailable) {
                val path = File("$sdkPath/libs-debug/").toString()
                configure<CrashlyticsExtension> {
                    nativeSymbolUploadEnabled = true
                    unstrippedNativeLibsDir = path
                }
            }
        }

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")

            val appVersion = gitVersion
            val appBranch = gitBranch
            println("Release flavor app version is [$appVersion], app branch is [$appBranch]")
            resValue("string", "linphone_app_version", appVersion)
            resValue("string", "linphone_app_branch", appBranch)
            resValue("string", "file_provider", "$packageName.fileprovider")
            resValue("string", "linphone_openid_callback_scheme", packageName)

            if (crashlyticsAvailable) {
                val path = File("$sdkPath/libs-debug/").toString()
                configure<CrashlyticsExtension> {
                    nativeSymbolUploadEnabled = true
                    unstrippedNativeLibsDir = path
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        dataBinding = true
        buildConfig = false
        resValues = true
    }

    sourceSets {
        getByName("main") {
            java.srcDir(credentialKeySourceDir)
        }
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Data binding generates androidx.databinding.DataBinderMapperImpl (instantiated by DataBindingUtil
// at startup). INCREMENTAL Java compilation was stashing that generated class and failing to restore
// it, so release builds shipped WITHOUT it and crashed at launch with NoClassDefFoundError. The code
// here is almost entirely Kotlin, so the javac step is tiny — force it non-incremental so the mapper
// is always compiled in. (The APK builder runs `assemble` without `clean`, which is what let the
// stale state persist.)
tasks.withType<JavaCompile>().configureEach {
    options.isIncremental = false
}

dependencies {
    implementation(libs.androidx.annotations)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraint.layout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.telecom)
    implementation(libs.androidx.media)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.slidingpanelayout)
    implementation(libs.androidx.window)
    implementation(libs.androidx.gridlayout)
    implementation(libs.androidx.security.crypto.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.emoji2)
    implementation(libs.androidx.car)

    // https://github.com/google/flexbox-layout/blob/main/LICENSE Apache v2.0
    implementation(libs.google.flexbox)
    // https://github.com/material-components/material-components-android/blob/master/LICENSE Apache v2.0
    implementation(libs.google.material)
    // To be able to parse native crash tombstone and print them with SDK logs the next time the app will start
    implementation(libs.google.protobuf)

    implementation(platform(libs.google.firebase.bom))
    implementation(libs.google.firebase.messaging)
    if (crashlyticsAvailable) {
        implementation(libs.google.firebase.crashlytics)
    } else {
        compileOnly(libs.google.firebase.crashlytics)
    }

    // https://github.com/coil-kt/coil/blob/main/LICENSE.txt Apache v2.0
    implementation(libs.coil)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.coil.video)
    // https://github.com/tommybuonomo/dotsindicator/blob/master/LICENSE Apache v2.0
    implementation(libs.dots.indicator)
    // https://github.com/Baseflow/PhotoView/blob/master/LICENSE Apache v2.0
    implementation(libs.photoview)
    // https://github.com/openid/AppAuth-Android/blob/master/LICENSE Apache v2.0
    implementation(libs.openid.appauth)

    implementation(libs.linphone)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
}

configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    android.set(true)
    ignoreFailures.set(true)
    additionalEditorconfig.set(
        mapOf(
            "max_line_length" to "120",
            "ktlint_standard_max-line-length" to "disabled",
            "ktlint_standard_function-signature" to "disabled",
            "ktlint_standard_no-blank-line-before-rbrace" to "disabled",
            "ktlint_standard_no-empty-class-body" to "disabled",
            "ktlint_standard_annotation-spacing" to "disabled",
            "ktlint_standard_class-signature" to "disabled",
            "ktlint_standard_function-expression-body" to "disabled",
            "ktlint_standard_function-type-modifier-spacing" to "disabled",
            "ktlint_standard_if-else-wrapping" to "disabled",
            "ktlint_standard_argument-list-wrapping" to "disabled",
            "ktlint_standard_trailing-comma-on-call-site" to "disabled",
            "ktlint_standard_trailing-comma-on-declaration-site" to "disabled",
            "ktlint_standard_no-empty-first-line-in-class-body" to "disabled",
            "ktlint_standard_no-empty-first-line-in-method-block" to "disabled",
            "ktlint_standard_no-trailing-spaces" to "disabled",
            "ktlint_standard_no-blank-line-in-list" to "disabled",
            "ktlint_standard_no-multi-spaces" to "disabled",
            "ktlint_standard_try-catch-finally-spacing" to "disabled",
            "ktlint_standard_block-comment-initial-star-alignment" to "disabled",
            "ktlint_standard_spacing-between-declarations-with-comments" to "disabled",
            "ktlint_standard_no-consecutive-comments" to "disabled",
            "ktlint_standard_multiline-expression-wrapping" to "disabled",
            "ktlint_standard_parameter-list-wrapping" to "disabled",
            "ktlint_standard_comment-wrapping" to "disabled",
            "ktlint_standard_discouraged-comment-location" to "disabled",
            "ktlint_standard_string-template-indent" to "disabled",
            "ktlint_standard_parameter-list-spacing" to "disabled",
            "ktlint_standard_statement-wrapping" to "disabled",
            "ktlint_standard_import-ordering" to "disabled",
            "ktlint_standard_paren-spacing" to "disabled",
            "ktlint_standard_curly-spacing" to "disabled",
            "ktlint_standard_indent" to "disabled",
        )
    )
}
project.tasks.preBuild.dependsOn("ktlintFormat")

if (crashlyticsAvailable) {
    afterEvaluate {
        tasks.getByName("assembleDebug").finalizedBy(
            tasks.getByName("uploadCrashlyticsSymbolFileDebug"),
        )
        tasks.getByName("packageDebug").finalizedBy(
            tasks.getByName("uploadCrashlyticsSymbolFileDebug"),
        )
        tasks.getByName("assembleRelease").finalizedBy(
            tasks.getByName("uploadCrashlyticsSymbolFileRelease"),
        )
        tasks.getByName("packageRelease").finalizedBy(
            tasks.getByName("uploadCrashlyticsSymbolFileRelease"),
        )
    }
}
