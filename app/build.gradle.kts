import groovy.json.JsonSlurper
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val gitVersion = providers.exec {
    commandLine("git", "describe", "--tags", "--match", "v[0-9]*")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().removePrefix("v").ifEmpty { "0.0.0" }
val versionParts = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)").find(gitVersion)?.destructured
    ?.toList()?.map { it.toInt() } ?: listOf(0, 0, 0)

android {
    namespace = "net.bannerlang.kb"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "net.bannerlang.kb"
        minSdk = 26
        targetSdk = 35
        versionCode = maxOf(1, versionParts[0] * 1_000_000 + versionParts[1] * 1_000 + versionParts[2])
        versionName = gitVersion
    }

    val releaseKey = file(System.getenv("RELEASE_KEY_PROPERTIES")
        ?: (System.getProperty("user.home") + "/keys/bannerlang_kb-release.properties"))
    signingConfigs {
        if (releaseKey.exists()) create("release") {
            val p = Properties().apply { releaseKey.inputStream().use { load(it) } }
            storeFile = file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

abstract class BannerTextures : DefaultTask() {
    @get:Input abstract val jarSha1: Property<String>
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    private fun sha1(f: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        f.inputStream().use { s ->
            val b = ByteArray(1 shl 16)
            while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @TaskAction
    fun extract() {
        val hash = jarSha1.get()
        val jar = cacheDir.file("client-$hash.jar").get().asFile
        if (!jar.exists() || sha1(jar) != hash) {
            jar.parentFile.mkdirs()
            val part = File(jar.path + ".part")
            URI("https://piston-data.mojang.com/v1/objects/$hash/client.jar").toURL().openStream()
                .use { i -> part.outputStream().use { i.copyTo(it) } }
            if (sha1(part) != hash) { part.delete(); throw GradleException("Downloaded client.jar doesn't match its SHA-1") }
            jar.delete()
            if (!part.renameTo(jar)) throw GradleException("Couldn't store client.jar in $jar")
        }

        val out = outputDir.get().asFile.resolve("textures")
        out.deleteRecursively()
        out.mkdirs()
        val tex = "assets/minecraft/textures/entity/banner/"
        val data = "data/minecraft/banner_pattern/"
        val safe = Regex("[a-z0-9_]+")
        val json = JsonSlurper()
        ZipFile(jar).use { z ->
            fun bytes(name: String) = z.getEntry(name)?.let { e -> z.getInputStream(e).use { it.readBytes() } }
            fun field(name: String, key: String) = (json.parseText(String(bytes(name)!!)) as Map<*, *>)[key] as String
            val lines = mutableListOf<String>()
            for (e in z.entries().asSequence()) {
                if (!e.name.startsWith(data) || !e.name.endsWith(".json")) continue
                val pid = e.name.removePrefix(data).removeSuffix(".json")
                val asset = field(e.name, "asset_id").substringAfter(':')
                if (!pid.matches(safe) || !asset.matches(safe)) continue
                val png = bytes("$tex$asset.png") ?: continue
                out.resolve("$asset.png").writeBytes(png)
                lines.add("$pid=$asset")
            }
            val cloth = bytes("${tex}banner_base.png") ?: throw GradleException("No banner_base.png in client.jar")
            out.resolve("banner_base.png").writeBytes(cloth)
            out.resolve("patterns.txt").writeText(lines.sorted().joinToString("\n"))
            out.resolve("version.txt").writeText(field("version.json", "id"))
        }
    }
}

val bannerTextures = tasks.register<BannerTextures>("bannerTextures") {
    jarSha1.set("e877b6a07acd633fb3bb475002175cec036e7b87")
    cacheDir.set(File(gradle.gradleUserHomeDir, "caches/bannerlang_kb"))
}

androidComponents {
    onVariants { v -> v.sources.assets?.addGeneratedSourceDirectory(bannerTextures, BannerTextures::outputDir) }
}
