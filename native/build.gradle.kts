// Build structure adapted from CakesTwix/cloudstream-extensions-uk (GPL-3.0).
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import com.android.build.gradle.BaseExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript { dependencies { classpath(libs.recloudstream.gradle) } }
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin) apply false
}
fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")
    cloudstream {
        // No fallback to CakesTwix: generated metadata must identify our own repository.
        val repository = System.getenv("GITHUB_REPOSITORY")
        if (!repository.isNullOrBlank()) setRepo(repository)
    }
    extensions.configure<BaseExtension>("android") {
        namespace = "ua.nuvio.rezka"
        compileSdkVersion(35)
        defaultConfig { minSdk = 21; targetSdk = 35 }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
            freeCompilerArgs.add("-opt-in=com.lagradost.cloudstream3.Prerelease")
        }
    }
    dependencies {
        add("cloudstream", rootProject.libs.cloudstream3)
        add("implementation", kotlin("stdlib"))
        // NativeHttp uses OkHttp directly; NiceHttp would pull OkHttp 5 into the 4.12 test runtime.
        add("implementation", rootProject.libs.kotlinx.coroutines.core)
        add("implementation", rootProject.libs.jsoup)
        // Nuvio already supplies OkHttp. Do not bundle duplicate runtime classes in DEX.
        add("compileOnly", "com.squareup.okhttp3:okhttp:4.12.0")
        add("testImplementation", "com.squareup.okhttp3:okhttp:4.12.0")
        add("testImplementation", "com.squareup.okhttp3:mockwebserver:4.12.0")
        // org.json is supplied by Android; JVM tests need a real implementation.
        add("testImplementation", "org.json:json:20240303")
        add("testImplementation", rootProject.libs.junit)
    }
}
