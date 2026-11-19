package com.offgrid.tracker

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.FileBasedTileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

enum class MapStyle(
    val id: String,
    val displayName: String,
    val description: String,
    val isDarkFilter: Boolean = false
) {
    OSM("osm", "Standart Harita (OSM)", "Net sokak, yol ve yer isimleri"),
    DARK("dark", "Koyu Taktik (Gece)", "Gece modu ve pil tasarrufu (OSM tabanlı)", isDarkFilter = true),
    TOPO("topo", "Arazi / Topoğrafik", "Yükselti eğrileri ve doğa rotaları"),
    SATELLITE("satellite", "Uydu Görüntüsü", "Yüksek çözünürlüklü uydu haritası (Esri)"),
    HGM_ATLAS("hgm_atlas", "HGM Atlas (Yerli — API Key)", "Harita Genel Müdürlüğü (Temel/Ortofoto/Topo)"),
    CUSTOM_CORPORATE("custom_corp", "Özel Kurumsal (TUCBS / WMS / XYZ)", "Kendi HTTPS WMTS, WMS veya XYZ sunucunuz"),
    OFFLINE_ARCHIVE("offline_archive", "Yerel Çevrimdışı Paket (.mbtiles)", "Cihazdan yüklenen tamamen internetsiz harita");

    companion object {
        fun fromId(id: String): MapStyle = values().firstOrNull { it.id == id } ?: OSM
    }
}

data class MbTilesMetadata(
    val name: String?,
    val center: GeoPoint?,
    val zoom: Double?,
    val bounds: BoundingBox?
)

sealed class ArchiveImportResult {
    data class Success(val file: File, val metadata: MbTilesMetadata?) : ArchiveImportResult()
    data class Error(val reason: String) : ArchiveImportResult()
}

data class MapApplyResult(
    val success: Boolean,
    val message: String? = null,
    val metadata: MbTilesMetadata? = null
)

object OfflineMapManager {

    const val MAX_OFFLINE_TILES_PER_DOWNLOAD = 600
    private const val WEB_MERCATOR_ORIGIN_SHIFT = 20037508.342789244 // pi * 6378137.0
    private const val OFFLINE_ARCHIVES_DIR = "osmdroid/offline_archives"
    private val ALLOWED_EXTENSIONS = setOf("mbtiles", "sqlite", "gemf", "zip")
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    val OSM_SOURCE: OnlineTileSourceBase = XYTileSource(
        "OSMStandardHTTPS",
        0, 19, 256, ".png",
        arrayOf("https://tile.openstreetmap.org/"),
        "© OpenStreetMap contributors"
    )

    val TOPO_SOURCE: OnlineTileSourceBase = XYTileSource(
        "OpenTopoMap",
        0, 17, 256, ".png",
        arrayOf(
            "https://a.tile.opentopomap.org/",
            "https://b.tile.opentopomap.org/",
            "https://c.tile.opentopomap.org/"
        ),
        "© OpenStreetMap contributors, SRTM | © OpenTopoMap"
    )

    val SATELLITE_SOURCE: OnlineTileSourceBase = object : OnlineTileSourceBase(
        "EsriWorldImagery",
        0, 18, 256, "",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
        "Tiles © Esri"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = MapTileIndex.getZoom(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            return "${baseUrl}$z/$y/$x"
        }
    }

    private val DARK_TACTICAL_FILTER: ColorMatrixColorFilter by lazy {
        val lr = 0.213f
        val lg = 0.715f
        val lb = 0.072f
        val matrix = ColorMatrix(
            floatArrayOf(
                1f - 2f * lr, -2f * lg, -2f * lb, 0f, 245f,
                -2f * lr, 1f - 2f * lg, -2f * lb, 0f, 245f,
                -2f * lr, -2f * lg, 1f - 2f * lb, 0f, 245f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        ColorMatrixColorFilter(matrix)
    }

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        val config = Configuration.getInstance()
        val prefs = appContext.getSharedPreferences("osmdroid_config", Context.MODE_PRIVATE)
        config.load(appContext, prefs)

        config.userAgentValue = "OffGridTrack/2.2 (Android; ${appContext.packageName})"

        val basePath = File(appContext.filesDir, "osmdroid").apply { mkdirs() }
        val tileCache = File(basePath, "tiles").apply { mkdirs() }
        File(appContext.filesDir, OFFLINE_ARCHIVES_DIR).mkdirs()

        config.osmdroidBasePath = basePath
        config.osmdroidTileCache = tileCache

        // Eski sürümden kalan CARTO önbelleğini temizle
        File(tileCache, "CartoVoyager").takeIf { it.exists() }?.deleteRecursively()
        File(tileCache, "CartoDark").takeIf { it.exists() }?.deleteRecursively()

        config.tileFileSystemCacheMaxBytes = 250L * 1024L * 1024L
        config.tileFileSystemCacheTrimBytes = 200L * 1024L * 1024L
        config.expirationOverrideDuration = 1000L * 60L * 60L * 24L * 30L
    }

    /**
     * Güvenlik: Yalnızca geçerli HTTPS URL'lerine izin verir (usesCleartextTraffic=false ile tam uyumlu).
     */
    fun validateHttpsUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) {
            return "Güvenlik gereği yalnızca 'https://' adresleri desteklenir."
        }
        return try {
            // Şablon yer tutucularını geçici değerle değiştirerek URI doğrulaması yap
            val testUrl = trimmed
                .replace("{z}", "10")
                .replace("{x}", "500")
                .replace("{y}", "300")
                .replace("{key}", "test")
                .replace("{layer}", "temel")
            val uri = URI(testUrl)
            if (uri.host.isNullOrBlank()) "Geçerli bir sunucu adresi (domain) girin." else null
        } catch (_: Exception) {
            "Geçersiz URL formatı."
        }
    }

    /**
     * Güvenlik: Kurumsal/HGM API başlıklarının genel harita sunucularına sızmasını önlemek için
     * her harita değişiminde özel HTTP başlıklarını sıfırlar.
     */
    private fun clearCustomHttpHeaders() {
        Configuration.getInstance().additionalHttpRequestProperties.clear()
    }

    private fun applySingleAuthHeader(rawHeader: String) {
        val trimmed = rawHeader.trim()
        if (trimmed.isEmpty()) return
        val props = Configuration.getInstance().additionalHttpRequestProperties
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx > 0) {
            val headerName = trimmed.substring(0, colonIdx).trim()
            val headerValue = trimmed.substring(colonIdx + 1).trim()
            if (headerName.isNotEmpty() && headerValue.isNotEmpty() &&
                !headerName.contains('\n') && !headerValue.contains('\n')
            ) {
                props[headerName] = headerValue
            }
        } else if (!trimmed.contains('\n')) {
            props["Authorization"] = trimmed
        }
    }

    private fun createHgmTileSource(config: HgmConfig): OnlineTileSourceBase {
        val safeLayer = config.layer.trim().ifBlank { HgmConfig.LAYER_TEMEL }
        val encodedKey = URLEncoder.encode(config.apiKey.trim(), "UTF-8")
        val template = config.urlTemplate.trim()
        val sourceName = "HGMAtlas_${safeLayer.replace(Regex("[^a-zA-Z0-9_]"), "")}"

        return object : OnlineTileSourceBase(
            sourceName,
            0, 19, 256, ".png",
            arrayOf("https://atlas.harita.gov.tr/"),
            "© Harita Genel Müdürlüğü (HGM Atlas)"
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex).toString()
                val x = MapTileIndex.getX(pMapTileIndex).toString()
                val y = MapTileIndex.getY(pMapTileIndex).toString()
                return template
                    .replace("{layer}", safeLayer)
                    .replace("{z}", z)
                    .replace("{x}", x)
                    .replace("{y}", y)
                    .replace("{key}", encodedKey)
            }
        }
    }

    private fun createCustomTileSource(config: CustomMapConfig): OnlineTileSourceBase {
        val safeName = "Custom_" + config.name.ifBlank { "Corp" }
            .replace(Regex("[^a-zA-Z0-9_]"), "")
            .take(24)
            .ifBlank { "Corp" }
        val template = config.urlTemplate.trim()

        return if (config.protocol == CustomMapConfig.PROTOCOL_WMS) {
            val encodedLayers = URLEncoder.encode(config.wmsLayers.trim(), "UTF-8")
            val sep = if (template.contains("?")) {
                if (template.endsWith("?") || template.endsWith("&")) "" else "&"
            } else "?"

            object : OnlineTileSourceBase(
                "${safeName}_WMS",
                0, 19, 256, ".png",
                arrayOf(template),
                "Özel Kurumsal WMS"
            ) {
                override fun getTileURLString(pMapTileIndex: Long): String {
                    val x = MapTileIndex.getX(pMapTileIndex)
                    val y = MapTileIndex.getY(pMapTileIndex)
                    val z = MapTileIndex.getZoom(pMapTileIndex)
                    val bbox = tileToWebMercatorBbox(x, y, z)
                    val bboxStr = String.format(
                        Locale.US,
                        "%.6f,%.6f,%.6f,%.6f",
                        bbox[0], bbox[1], bbox[2], bbox[3]
                    )
                    return "${template}${sep}SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap" +
                        "&LAYERS=$encodedLayers&STYLES=&SRS=EPSG:3857" +
                        "&WIDTH=256&HEIGHT=256&FORMAT=image/png&TRANSPARENT=true&BBOX=$bboxStr"
                }
            }
        } else {
            object : OnlineTileSourceBase(
                "${safeName}_XYZ",
                0, 19, 256, ".png",
                arrayOf(template),
                "Özel Kurumsal XYZ/WMTS"
            ) {
                override fun getTileURLString(pMapTileIndex: Long): String {
                    val z = MapTileIndex.getZoom(pMapTileIndex).toString()
                    val x = MapTileIndex.getX(pMapTileIndex).toString()
                    val y = MapTileIndex.getY(pMapTileIndex).toString()
                    return template
                        .replace("{z}", z)
                        .replace("{x}", x)
                        .replace("{y}", y)
                }
            }
        }
    }

    private fun tileToWebMercatorBbox(x: Int, y: Int, zoom: Int): DoubleArray {
        val tileCount = (1 shl zoom).toDouble()
        val tileSizeMeters = (2.0 * WEB_MERCATOR_ORIGIN_SHIFT) / tileCount
        val minX = -WEB_MERCATOR_ORIGIN_SHIFT + x * tileSizeMeters
        val maxX = -WEB_MERCATOR_ORIGIN_SHIFT + (x + 1) * tileSizeMeters
        val maxY = WEB_MERCATOR_ORIGIN_SHIFT - y * tileSizeMeters
        val minY = WEB_MERCATOR_ORIGIN_SHIFT - (y + 1) * tileSizeMeters
        return doubleArrayOf(minX, minY, maxX, maxY)
    }

    fun applyMapStyle(
        context: Context,
        mapView: MapView,
        style: MapStyle,
        storage: SecureStorage
    ): MapApplyResult {
        // Her geçişte eski özel HTTP başlıklarını temizle (cross-domain kimlik sızıntısı önlenir)
        clearCustomHttpHeaders()

        if (style == MapStyle.OFFLINE_ARCHIVE) {
            val fileName = storage.getActiveOfflineArchive()
            val archiveFile = getOfflineArchiveFile(context, fileName)
            if (archiveFile == null || !archiveFile.exists()) {
                return applyOnlineStyle(context, mapView, OSM_SOURCE, darkFilter = false).let {
                    MapApplyResult(false, "Aktif çevrimdışı paket bulunamadı. Önce .mbtiles/.sqlite dosyası yükleyin.")
                }
            }
            return applyOfflineArchive(context, mapView, archiveFile)
        }

        val tileSource: OnlineTileSourceBase = when (style) {
            MapStyle.OSM, MapStyle.DARK -> OSM_SOURCE
            MapStyle.TOPO -> TOPO_SOURCE
            MapStyle.SATELLITE -> SATELLITE_SOURCE
            MapStyle.HGM_ATLAS -> {
                val hgm = storage.getHgmConfig()
                if (!hgm.isConfigured) {
                    applyOnlineStyle(context, mapView, OSM_SOURCE, darkFilter = false)
                    return MapApplyResult(false, "HGM Atlas için önce API anahtarınızı yapılandırın.")
                }
                if (hgm.referer.isNotBlank() && !hgm.referer.contains('\n')) {
                    Configuration.getInstance().additionalHttpRequestProperties["Referer"] = hgm.referer.trim()
                }
                createHgmTileSource(hgm)
            }
            MapStyle.CUSTOM_CORPORATE -> {
                val custom = storage.getCustomMapConfig()
                if (!custom.isConfigured) {
                    applyOnlineStyle(context, mapView, OSM_SOURCE, darkFilter = false)
                    return MapApplyResult(false, "Özel kurumsal harita URL'si henüz tanımlı değil.")
                }
                applySingleAuthHeader(custom.authHeader)
                createCustomTileSource(custom)
            }
            MapStyle.OFFLINE_ARCHIVE -> OSM_SOURCE
        }

        return applyOnlineStyle(context, mapView, tileSource, darkFilter = style.isDarkFilter)
    }

    private fun applyOnlineStyle(
        context: Context,
        mapView: MapView,
        tileSource: OnlineTileSourceBase,
        darkFilter: Boolean
    ): MapApplyResult {
        // Eğer önceki sağlayıcı OfflineTileProvider ise normal MapTileProviderBasic'e geri dön
        if (mapView.tileProvider is OfflineTileProvider) {
            try {
                mapView.tileProvider.detach()
            } catch (_: Exception) {
            }
            mapView.tileProvider = MapTileProviderBasic(context.applicationContext, tileSource)
        } else {
            mapView.setTileSource(tileSource)
        }
        mapView.setUseDataConnection(true)
        mapView.overlayManager.tilesOverlay.setColorFilter(
            if (darkFilter) DARK_TACTICAL_FILTER else null
        )
        mapView.invalidate()
        return MapApplyResult(true)
    }

    private fun applyOfflineArchive(
        context: Context,
        mapView: MapView,
        archiveFile: File
    ): MapApplyResult {
        return try {
            try {
                mapView.tileProvider?.detach()
            } catch (_: Exception) {
            }
            val offlineProvider = OfflineTileProvider(
                SimpleRegisterReceiver(context.applicationContext),
                arrayOf(archiveFile)
            )
            mapView.tileProvider = offlineProvider
            mapView.setUseDataConnection(false)

            // Arşiv içindeki kaynak adını tespit et (MOBAC/QGIS SQLite veya MBTiles)
            val sourceName = offlineProvider.archives
                ?.firstOrNull()
                ?.tileSources
                ?.firstOrNull()

            if (!sourceName.isNullOrBlank()) {
                mapView.setTileSource(FileBasedTileSource.getSource(sourceName))
            } else {
                mapView.setTileSource(TileSourceFactory.DEFAULT_TILE_SOURCE)
            }

            mapView.overlayManager.tilesOverlay.setColorFilter(null)
            mapView.invalidate()

            val meta = if (archiveFile.extension.equals("mbtiles", ignoreCase = true)) {
                readMbTilesMetadata(archiveFile)
            } else null

            MapApplyResult(true, "Çevrimdışı harita yüklendi: ${archiveFile.name}", meta)
        } catch (e: Exception) {
            applyOnlineStyle(context, mapView, OSM_SOURCE, darkFilter = false)
            MapApplyResult(false, "Arşiv okunamadı: ${e.message}")
        }
    }

    // ==================== YEREL ÇEVRİMDIŞI ARŞİV (.mbtiles / .sqlite) ====================

    fun getOfflineArchivesDir(context: Context): File {
        return File(context.applicationContext.filesDir, OFFLINE_ARCHIVES_DIR).apply { mkdirs() }
    }

    fun getOfflineArchiveFile(context: Context, fileName: String): File? {
        if (fileName.isBlank()) return null
        // Path traversal engeli: yalnızca düz dosya adına izin ver
        val safeName = File(fileName).name
        if (safeName != fileName || safeName.contains("..")) return null
        return File(getOfflineArchivesDir(context), safeName)
    }

    fun listOfflineArchives(context: Context): List<File> {
        return getOfflineArchivesDir(context)
            .listFiles()
            ?.filter { it.isFile && ALLOWED_EXTENSIONS.contains(it.extension.lowercase(Locale.US)) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun deleteOfflineArchive(context: Context, fileName: String): Boolean {
        val file = getOfflineArchiveFile(context, fileName) ?: return false
        return file.delete()
    }

    /**
     * SAF (OpenDocument) ile seçilen harita dosyasını ek depolama izni istemeden
     * güvenli dosya adı ve sihirli bayt (magic header) doğrulamasıyla uygulama alanına kopyalar.
     */
    fun importOfflineArchive(context: Context, uri: Uri, originalDisplayName: String): ArchiveImportResult {
        val baseName = originalDisplayName.substringAfterLast('/').substringAfterLast('\\').trim()
        val ext = baseName.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext !in ALLOWED_EXTENSIONS) {
            return ArchiveImportResult.Error(
                "Desteklenmeyen dosya türü (.$ext). Yalnızca .mbtiles, .sqlite, .gemf veya .zip yükleyebilirsiniz."
            )
        }

        val sanitizedStem = baseName.substringBeforeLast('.')
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .take(48)
            .ifBlank { "offline_map" }
        val targetFile = File(getOfflineArchivesDir(context), "$sanitizedStem.$ext")

        return try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return ArchiveImportResult.Error("Dosya okunamadı.")

            input.use { stream ->
                val header = ByteArray(16)
                val readHeader = stream.read(header)
                if (readHeader < 4) {
                    return ArchiveImportResult.Error("Dosya boş veya bozuk.")
                }

                // Sihirli bayt doğrulaması (SQLite / ZIP)
                if ((ext == "mbtiles" || ext == "sqlite") &&
                    (readHeader < SQLITE_MAGIC.size || !header.copyOfRange(0, SQLITE_MAGIC.size).contentEquals(SQLITE_MAGIC))
                ) {
                    return ArchiveImportResult.Error("Geçersiz SQLite / MBTiles dosya başlığı.")
                }
                if (ext == "zip" && !(header[0] == 0x50.toByte() && header[1] == 0x4B.toByte())) {
                    return ArchiveImportResult.Error("Geçersiz ZIP arşivi.")
                }

                FileOutputStream(targetFile).use { out ->
                    out.write(header, 0, readHeader)
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                    }
                }
            }

            val meta = if (ext == "mbtiles") readMbTilesMetadata(targetFile) else null
            ArchiveImportResult.Success(targetFile, meta)
        } catch (e: Exception) {
            targetFile.delete()
            ArchiveImportResult.Error("İçe aktarma hatası: ${e.message}")
        }
    }

    fun readMbTilesMetadata(file: File): MbTilesMetadata? {
        return try {
            val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            db.use {
                val values = HashMap<String, String>()
                db.rawQuery("SELECT name, value FROM metadata", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val k = cursor.getString(0)?.lowercase(Locale.US) ?: continue
                        val v = cursor.getString(1) ?: continue
                        values[k] = v
                    }
                }

                val name = values["name"]
                var centerPoint: GeoPoint? = null
                var zoom: Double? = null

                values["center"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }?.let { parts ->
                    if (parts.size >= 2) {
                        // MBTiles spec: center = lon, lat, zoom
                        centerPoint = GeoPoint(parts[1], parts[0])
                        if (parts.size >= 3) zoom = parts[2]
                    }
                }

                var boundsBox: BoundingBox? = null
                values["bounds"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }?.let { parts ->
                    if (parts.size == 4) {
                        // MBTiles spec: bounds = west, south, east, north
                        boundsBox = BoundingBox(parts[3], parts[2], parts[1], parts[0])
                        if (centerPoint == null) {
                            centerPoint = GeoPoint((parts[1] + parts[3]) / 2.0, (parts[0] + parts[2]) / 2.0)
                        }
                    }
                }

                MbTilesMetadata(name = name, center = centerPoint, zoom = zoom, bounds = boundsBox)
            }
        } catch (_: Exception) {
            null
        }
    }

    // ==================== ÇEVRİMDIŞI BÖLGE İNDİRİCİ & ÖNBELLEK ====================

    fun estimateDownloadTileCount(mapView: MapView): Triple<Int, Int, Int> {
        val currentZoom = mapView.zoomLevelDouble.toInt().coerceIn(8, 16)
        val minZoom = (currentZoom - 1).coerceAtLeast(8)
        val maxZoom = (currentZoom + 2).coerceAtMost(mapView.tileProvider.tileSource.maximumZoomLevel.coerceAtMost(16))
        val cacheManager = CacheManager(mapView)
        val count = cacheManager.possibleTilesInArea(mapView.boundingBox, minZoom, maxZoom)
        return Triple(count, minZoom, maxZoom)
    }

    fun downloadVisibleRegion(
        mapView: MapView,
        onProgress: (done: Int, total: Int) -> Unit,
        onComplete: (downloaded: Int, errors: Int) -> Unit,
        onTooLarge: (estimated: Int) -> Unit
    ): CacheManager.CacheManagerTask? {
        val (estimated, minZoom, maxZoom) = estimateDownloadTileCount(mapView)
        if (estimated > MAX_OFFLINE_TILES_PER_DOWNLOAD) {
            onTooLarge(estimated)
            return null
        }

        val bbox: BoundingBox = mapView.boundingBox
        val cacheManager = CacheManager(mapView)
        return cacheManager.downloadAreaAsyncNoUI(
            mapView.context,
            bbox,
            minZoom,
            maxZoom,
            object : CacheManager.CacheManagerCallback {
                override fun onTaskComplete() {
                    onComplete(estimated, 0)
                }

                override fun onTaskFailed(errors: Int) {
                    onComplete((estimated - errors).coerceAtLeast(0), errors)
                }

                override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                    onProgress(progress, estimated)
                }

                override fun downloadStarted() {
                    onProgress(0, estimated)
                }

                override fun setPossibleTilesInArea(total: Int) {
                    onProgress(0, total)
                }
            }
        )
    }

    fun getCacheSizeFormatted(context: Context): String {
        val cacheDir = File(context.applicationContext.filesDir, "osmdroid/tiles")
        val archiveDir = getOfflineArchivesDir(context)
        val bytes = folderSize(cacheDir) + folderSize(archiveDir)
        return formatBytes(bytes)
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    fun clearTileCache(context: Context) {
        val cacheDir = File(context.applicationContext.filesDir, "osmdroid/tiles")
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
    }

    private fun folderSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.listFiles()?.forEach { file ->
            total += if (file.isDirectory) folderSize(file) else file.length()
        }
        return total
    }
}
