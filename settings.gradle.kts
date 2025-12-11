pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        flatDir {
            dirs("app/libs")
        }
    }
}

rootProject.name = "FadCam"
include(":app")

// Include patched Media3 as composite build for live streaming support
// Clone it once: git clone --depth 1 https://github.com/anonfaded/media3-patched.git /tmp/media3-patched
// Or set your own path in local.properties: media3.patched.path=/your/path
val media3PatchedPath = if (file("local.properties").exists()) {
    val props = java.util.Properties()
    file("local.properties").inputStream().use { props.load(it) }
    props.getProperty("media3.patched.path", "/tmp/media3-patched")
} else {
    "/tmp/media3-patched"
}

if (file(media3PatchedPath).exists()) {
    includeBuild(media3PatchedPath) {
        dependencySubstitution {
            substitute(module("androidx.media3:media3-muxer")).using(project(":lib-muxer"))
            substitute(module("androidx.media3:media3-common")).using(project(":lib-common"))
            substitute(module("androidx.media3:media3-container")).using(project(":lib-container"))
        }
    }
} else {
    logger.warn("⚠️ Patched Media3 not found at: $media3PatchedPath")
    logger.warn("📥 Clone it with: git clone --depth 1 https://github.com/anonfaded/media3-patched.git $media3PatchedPath")
}
 