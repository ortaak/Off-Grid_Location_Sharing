package com.offgrid.tracker

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import java.io.File
import java.util.Locale

enum class MapStyle(
    val id: String,
    val displayName: String,
    val description: String,
    val isDarkFilter: Boolean = false
) {
    OSM("osm", "Standart Harita (OSM)", "Net sokak, yol ve yer isimleri (Önerilen)"),
    DARK("dark", "Koyu Taktik (Gece)", "Gece modu ve OLED pil tasarrufu (OSM tabanlı)", isDarkFilter = true),
    TOPO("topo", "Arazi / Topoğrafik", "Yükselti eğrileri ve doğa rotaları"),
    SATELLITE("satellite", "Uydu Görüntüsü", "Yüksek çözünürlüklü uydu haritası (Esri)");

    companion object {
        fun fromId(id: String): MapStyle = values().firstOrNull { it.id == id } ?: OSM
    }
}

object OfflineMapManager {

    const val MAX_OFFLINE_TILES_PER_DOWNLOAD = 600

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

    // Esri ArcGIS World Imagery: API anahtarı gerektirmez ({z}/{y}/{x} sırasıyla çalışır)
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

    /**
     * OSM haritası için renk tonunu koruyan Koyu Taktik filtresi (Invert + 180° Hue Rotation).
     * Denizler koyu lacivert, parklar koyu yeşil, yollar ve yazılar yüksek kontrastlı açık ton olur.
     */
    private val DARK_TACTICAL_FILTER: ColorMatrixColorFilter by lazy {
        // Luminance weights (Rec.709): lr=0.213, lg=0.715, lb=0.072
        // Invert(HueRotate180(RGB)) = 255 - M_hue180 * RGB
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

    /**
     * OSMDroid yapılandırmasını Android 10-14 (Scoped Storage) ile tam uyumlu
     * olacak şekilde uygulama dahili depolama alanına kurar.
     * Mutlaka setContentView / MapView oluşturulmadan önce çağrılmalıdır.
     */
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        val config = Configuration.getInstance()
        val prefs = appContext.getSharedPreferences("osmdroid_config", Context.MODE_PRIVATE)
        config.load(appContext, prefs)

        // OSM sunucuları geçerli ve açıklayıcı User-Agent zorunlu kılar
        config.userAgentValue = "OffGridTrack/2.1 (Android; ${appContext.packageName})"

        // Android 10+ Scoped Storage uyumlu yazılabilir önbellek dizini
        val basePath = File(appContext.filesDir, "osmdroid").apply { mkdirs() }
        val tileCache = File(basePath, "tiles").apply { mkdirs() }
        config.osmdroidBasePath = basePath
        config.osmdroidTileCache = tileCache

        // Eski sürümden kalan CARTO (API key isteyen) önbellek klasörlerini otomatik temizle
        File(tileCache, "CartoVoyager").takeIf { it.exists() }?.deleteRecursively()
        File(tileCache, "CartoDark").takeIf { it.exists() }?.deleteRecursively()

        // Çevrimdışı önbellek boyut limitleri (250 MB)
        config.tileFileSystemCacheMaxBytes = 250L * 1024L * 1024L
        config.tileFileSystemCacheTrimBytes = 200L * 1024L * 1024L
        config.expirationOverrideDuration = 1000L * 60L * 60L * 24L * 30L // 30 gün önbellek
    }

    fun getTileSource(style: MapStyle): OnlineTileSourceBase {
        return when (style) {
            MapStyle.OSM, MapStyle.DARK -> OSM_SOURCE
            MapStyle.TOPO -> TOPO_SOURCE
            MapStyle.SATELLITE -> SATELLITE_SOURCE
        }
    }

    fun applyMapStyle(mapView: MapView, style: MapStyle) {
        mapView.setTileSource(getTileSource(style))
        mapView.overlayManager.tilesOverlay.setColorFilter(
            if (style.isDarkFilter) DARK_TACTICAL_FILTER else null
        )
        mapView.invalidate()
    }

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
        val bytes = folderSize(cacheDir)
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
