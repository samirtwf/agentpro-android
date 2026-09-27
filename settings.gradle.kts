pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()

        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroup ("com.github.chrisbanes")
            }
        }

        // AgentPro: the SDK comes from a copy inside this project, never from linphone.org.
        //
        // Upstream resolved org.linphone against https://download.linphone.org/maven_repository
        // whenever no locally built SDK was configured, which is the normal case. Combined with
        // the version range that used to be in libs.versions.toml ("5.5.+"), every build had to
        // ask their server which version was newest — so building the app was itself a request
        // to them, from whatever machine happened to be doing the build.
        //
        // linphone-sdk-local/ holds the exact .aar and .pom the app is pinned to, and the pin in
        // libs.versions.toml is an exact version, so nothing has to be looked up. The build now
        // works with the network unplugged.
        //
        // Resolved against settingsDir rather than an absolute path, so moving or copying the
        // whole folder keeps working. LinphoneSdkBuildDir still overrides it if you ever build
        // the SDK yourself — but there is no remote fallback: a missing SDK fails the build
        // loudly instead of quietly fetching it from them.
        val sdkOverride = providers.gradleProperty("LinphoneSdkBuildDir").orNull?.takeIf { it.isNotBlank() }
        val sdkRoot = if (sdkOverride != null) File(sdkOverride) else File(settingsDir, "linphone-sdk-local")
        val sdkRepo = File(sdkRoot, "maven_repository")
        require(File(sdkRepo, "org/linphone/linphone-sdk-android").isDirectory) {
            "Linphone SDK not found at ${sdkRepo.absolutePath}. This build never downloads it — " +
                "restore linphone-sdk-local/, or point LinphoneSdkBuildDir at your own SDK build."
        }
        println("Using the vendored SDK at ${sdkRepo.absolutePath} (no remote repository)")
        maven {
            name = "vendored linphone-sdk maven repository"
            url = sdkRepo.toURI()
            content {
                includeGroup("org.linphone")
            }
        }
    }
}

rootProject.name = "ProAgent"
include(":app")
