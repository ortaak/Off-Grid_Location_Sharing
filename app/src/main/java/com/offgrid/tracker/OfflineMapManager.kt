package com.offgrid.tracker

import android.content.Context
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView
import java.io.File
import java.util.Locale

enum class MapStyle(val id: String, val displayName: String, val description: String) {
    VOYAGER("voyager", "Modern Harita", "Net sokak ve yol görünümü (Önerilen)"),
    DARK("dark", "Koyu Taktik", "Gece kullanımı ve pil tasarrufu"),
    TOPO("topo", "Arazi / Topoğrafik", "Yükselti eğrileri ve doğa rotaları"),
    OSM("osm", "OpenStreetMap", "Klasik OSM standart harita");

    companion object {
        fun fromId(id: String): MapStyle = values().firstOrNull { it.id == id } ?: VOYAGER
    }
}

object OfflineMapManager {

    const val MAX_OFFLINE_TILES_PER_DOWNLOAD = 600

    val VOYAGER_SOURCE: OnlineTileSourceBase = XYTileSource(
        "CartoVoyager",
        0, 19, 256, ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
            "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
            "https://c.basemaps.cartocdn.com/rastertiles/voyager/",
            "https://d.basemaps.cartocdn.com/rastertiles/voyager/"
        ),
        "© OpenStreetMap contributors, © CARTO"
    )

    val DARK_SOURCE: OnlineTileSourceBase = XYTileSource(
        "CartoDark",
        0, 19, 256, ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/dark_all/",
            "https://b.basemaps.cartocdn.com/dark_all/",
            "https://c.basemaps.cartocdn.com/dark_all/",
            "https://d.basemaps.cartocdn.com/dark_all/"
        ),
        "© OpenStreetMap contributors, © CARTO"
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

    val OSM_SOURCE: OnlineTileSourceBase = XYTileSource(
        "OSMStandardHTTPS",
        0, 19, 256, ".png",
        arrayOf("https://tile.openstreetmap.org/"),
        "© OpenStreetMap contributors"
    )

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
        config.userAgentValue = "OffGridTrack/2.0 (Android; ${appContext.packageName})"

        // Android 10+ Scoped Storage uyumlu yazılabilir önbellek dizini
        val basePath = File(appContext.filesDir, "osmdroid").apply { mkdirs() }
        val tileCache = File(basePath, "tiles").apply { mkdirs() }
        config.osmdroidBasePath = basePath
        config.osmdroidTileCache = tileCache

        // Çevrimdışı önbellek boyut limitleri (250 MB)
        config.tileFileSystemCacheMaxBytes = 250L * 1024L * 1024L
        config.tileFileSystemCacheTrimBytes = 200L * 1024L * 1024L
        config.expirationOverrideDuration = 1000L * 60L * 60L * 24L * 30L // 30 gün önbellek
    }

    fun getTileSource(style: MapStyle): OnlineTileSourceBase {
        return when (style) {
            MapStyle.VOYAGER -> VOYAGER_SOURCE
            MapStyle.DARK -> DARK_SOURCE
            MapStyle.TOPO -> TOPO_SOURCE
            MapStyle.OSM -> OSM_SOURCE
        }
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
