package net.bannerlang.kb

import android.content.Context
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

object Textures {
    private const val TAG = "Textures"
    const val JAR_VERSION = "26.3"
    private const val TEX_PREFIX = "assets/minecraft/textures/entity/banner/"
    private const val DATA_PREFIX = "data/minecraft/banner_pattern/"
    private const val CLOTH = "banner_base"

    private lateinit var dir: File
    private lateinit var assets: AssetManager
    private const val ASSET_DIR = "textures"

    @Volatile var version: String? = null
        private set
    @Volatile var generation = 0
        private set
    @Volatile private var cloth: IntArray? = null
    @Volatile private var faces: Map<String, IntArray> = emptyMap()
    @Volatile var patternIds: List<String> = emptyList()
        private set

    val installed get() = cloth != null
    @Volatile var builtIn = false
        private set

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.filesDir, "textures")
        assets = ctx.assets
        load()
    }

    fun cloth(): IntArray? = cloth

    fun face(patternId: String): IntArray? = faces[patternId]

    private fun load() {
        val picked = File(dir, "version.txt").exists()
        val open: (String) -> InputStream =
            if (picked) { name -> File(dir, name).inputStream() } else { name -> assets.open("$ASSET_DIR/$name") }
        try {
            val v = open("version.txt").use { String(it.readBytes()) }
            val c = open("$CLOTH.png").use { readFace(it) } ?: return
            val map = LinkedHashMap<String, IntArray>()
            open("patterns.txt").use { String(it.readBytes()) }.lines().filter { '=' in it }.forEach { line ->
                val (id, asset) = line.split('=', limit = 2)
                open("$asset.png").use { readFace(it) }?.let { map[id] = it }
            }
            faces = map
            patternIds = map.keys.toList()
            cloth = c
            version = v.trim()
            builtIn = !picked
            generation++
        } catch (e: Exception) {
            Log.e(TAG, "could not load textures", e)
        }
    }

    private fun readFace(input: InputStream): IntArray? {
        val opts = BitmapFactory.Options().apply { inPremultiplied = false; inScaled = false }
        val bmp = BitmapFactory.decodeStream(input, null, opts) ?: return null
        val s = bmp.width / 64
        if (s < 1 || bmp.height < 41 * s) return null
        val out = IntArray(Patterns.W * Patterns.H)
        for (y in 0 until Patterns.H) for (x in 0 until Patterns.W) {
            out[y * Patterns.W + x] = bmp.getPixel((1 + x) * s, (1 + y) * s)
        }
        bmp.recycle()
        return out
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    var status: String? = null
        private set
    var busy = false
        private set

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private fun post(f: () -> Unit) = main.post { f(); listeners.toList().forEach { it() } }

    fun startInstall(ctx: Context, uri: Uri) {
        if (busy) return
        busy = true
        status = "Reading file…"
        listeners.toList().forEach { it() }
        val resolver = ctx.applicationContext.contentResolver
        Thread {
            val error = try {
                val input = resolver.openInputStream(uri) ?: throw IOException("can't open file")
                input.use { install(it) }
                null
            } catch (e: Exception) {
                Log.e(TAG, "texture import failed", e)
                "Couldn't use that file: ${e.message ?: e.javaClass.simpleName}"
            }
            post { busy = false; status = error }
        }.start()
    }

    fun install(input: InputStream) {
        val (id, files) = readJar(input)
        val lines = mutableListOf<String>()
        val pngs = mutableMapOf<String, ByteArray>()
        val safe = Regex("[a-z0-9_]+")
        for ((name, bytes) in files) {
            if (!name.startsWith(DATA_PREFIX)) continue
            val pid = name.removePrefix(DATA_PREFIX).removeSuffix(".json")
            val asset = JSONObject(String(bytes)).getString("asset_id").substringAfter(':')
            if (!pid.matches(safe) || !asset.matches(safe)) continue
            val png = files[TEX_PREFIX + asset + ".png"] ?: continue
            pngs[asset] = png
            lines.add("$pid=$asset")
        }
        val cloth = files[TEX_PREFIX + CLOTH + ".png"] ?: throw IOException("not a Minecraft client jar")
        if (lines.isEmpty()) throw IOException("no banner patterns found")

        val tmp = File(dir.parentFile, "textures.tmp").apply { deleteRecursively(); mkdirs() }
        File(tmp, "$CLOTH.png").writeBytes(cloth)
        pngs.forEach { (asset, b) -> File(tmp, "$asset.png").writeBytes(b) }
        File(tmp, "patterns.txt").writeText(lines.sorted().joinToString("\n"))
        File(tmp, "version.txt").writeText(id)
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) throw IOException("could not store textures")
        load()
        if (!installed) throw IOException("textures could not be decoded")
    }

    internal fun readJar(input: InputStream): Pair<String, Map<String, ByteArray>> {
        var id = "?"
        val out = mutableMapOf<String, ByteArray>()
        val zin = ZipInputStream(input.buffered())
        while (true) {
            val e = zin.nextEntry ?: break
            val n = e.name
            if (n == "version.json") {
                id = JSONObject(String(zin.readBytes())).optString("id", id)
            } else if ((n.startsWith(TEX_PREFIX) && n.endsWith(".png")) || (n.startsWith(DATA_PREFIX) && n.endsWith(".json"))) {
                out[n] = ByteArrayOutputStream().also { zin.copyTo(it) }.toByteArray()
            }
        }
        return id to out
    }
}
