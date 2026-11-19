# OffGridTrack v2.0 — İnternetsiz Güvenli Grup Konum Paylaşımı (Android)

İnternet bağlantısı olmadan, doğrudan uydu GPS'i ve uçtan uca şifreli SMS (`OGT2:`) protokolü kullanarak iki veya daha fazla Android cihaz arasında mesafe sınırı olmaksızın güvenli canlı konum takibi sağlar.

---

## ✨ v2.0 Yenilikleri

### 1. Modern Material 3 Arayüz ve Kullanıcı Deneyimi
- **Tam Ekran Etkileşimli Harita:** Üstte yüzen telemetri/güvenlik kartı (dokunarak koordinat kopyalama), sağda hızlı harita aksiyonları (**Konumum**, **Tüm Grubu Göster**, **Harita Katmanları**, **Şimdi Gönder**) ve altta sürüklenebilir **Bottom Sheet** kontrol paneli.
- **Çoklu Grup Takibi:** Aynı ortak anahtarla birden fazla kişiyi renk kodlu işaretçiler, kesikli mesafe/yön (`km • KD`) bağlantı çizgileri ve yatay canlı kişi çubuğuyla haritada takip edin.
- **Rehberden Tek Tıkla Ekleme:** Ek rehber izni istemeden (`ACTION_PICK`) doğrudan rehberden kişi adı ve numarası seçimi.
- **Arka Plan Ön Plan Servisi (`LocationTrackingService`):** Ekran kapansa bile GPS takibi ve periyodik şifreli gönderim kalıcı bildirimle devam eder.

### 2. Harita ve Çevrimdışı Önbellek Düzeltmeleri
- Android 10–14 (Scoped Storage) uyumlu dahili OSMDroid önbellek dizini (`filesDir/osmdroid/tiles`), uyumlu `User-Agent`, `INTERNET` izni ve tam `onResume`/`onPause` yaşam döngüsü eklendi.
- **4 Farklı API-Key Gerektirmeyen HTTPS Harita Stili:** Standart Harita (OpenStreetMap), Koyu Taktik / Gece Modu (OSM + renk tonu koruyan koyu filtre), Arazi/Topoğrafik (OpenTopoMap) ve Uydu Görüntüsü (Esri World Imagery).
- **Yerli & Kurumsal Harita Desteği (v2.2):**
  - **HGM Atlas (`api.harita.gov.tr`):** Kendi API anahtarınızla Temel, Ortofoto, Topoğrafik ve Gece katmanları (Android Keystore şifreli).
  - **Özel Kurumsal Sunucu (TUCBS / WMS / XYZ):** Yalnızca HTTPS destekli özel XYZ/WMTS veya OGC WMS (`EPSG:3857`) katmanları ve izole yetki başlığı desteği.
  - **Yerel Çevrimdışı Harita Paketleri (`.mbtiles` / `.sqlite` / `.gemf` / `.zip`):** Ek depolama izni istemeden (SAF) dosya başlığı doğrulamasıyla cihazdan tamamen internetsiz harita yükleme.
- **Dahili Çevrimdışı Bölge İndirici:** İnternet varken haritadaki mevcut alanı tek tıkla cihaza önbelleğe alın; arazide tamamen internetsiz kullanın.

### 3. Sıkılaştırılmış Güvenlik Mimarisi
- **AES-256-GCM + PBKDF2-HMAC-SHA256 (65.536 tur) + Paket Başı Rastgele Salt:** Her SMS benzersiz 8 bayt Salt ve 12 bayt IV ile üretilir, `OGT2:` başlığı GCM AAD olarak doğrulanır (toplam ~97 karakter — tek SMS).
- **Replay (Tekrar) Saldırısı Koruması:** Şifreli zaman damgası pencere kontrolü, gönderici bazlı monotonik zaman kontrolü ve görülen IV önbelleği.
- **Gönderen Numara Beyaz Listesi:** Yalnızca izinli grup listesinde kayıtlı numaralardan gelen paketler işlenir; bilinmeyen numaralar şifre çözülmeden reddedilir.
- **Güçlü Anahtar Zorunluluğu + Üretici:** En az 10 karakter zorunluluğu, canlı güç göstergesi, 16 karakterli kriptografik rastgele anahtar üretici ve Android 13+ hassas pano kopyalama (`EXTRA_IS_SENSITIVE`).
- **Ekran Güvenliği (`FLAG_SECURE`) & Android Keystore:** Ekran görüntüsü ve son uygulamalar önizlemesi engellenir; yerel ayarlar ve anahtar Android Keystore donanım destekli AES-256-GCM ile şifrelenir (`allowBackup="false"`, `usesCleartextTraffic="false"`).

---

## 📁 Proje Yapısı

```text
OffGridTrack/
├── .github/workflows/build-apk.yml          # GitHub Actions otomatik APK derleme
└── app/src/main/
    ├── AndroidManifest.xml                  # İzinler, servis ve güvenlik bayrakları
    ├── java/com/offgrid/tracker/
    │   ├── CryptoHelper.kt                  # AES-256-GCM, PBKDF2, Replay koruması, anahtar üretici
    │   ├── PhoneNumberUtils.kt              # Telefon numarası normalizasyonu ve beyaz liste eşleştirme
    │   ├── SecureStorage.kt                 # Android Keystore destekli şifreli yerel depolama
    │   ├── OfflineMapManager.kt             # OSMDroid Scoped Storage, 4 HTTPS harita stili, çevrimdışı indirici
    │   ├── PeerState.kt                     # Grup üyeleri mesafe, yön (pusula) ve güncellik modeli
    │   ├── LocationSmsReceiver.kt           # Çok parçalı SMS birleştirici & OGT2 paket yakalayıcı
    │   ├── LocationTrackingService.kt       # Arka plan Foreground GPS & şifreli yayın servisi
    │   └── MainActivity.kt                  # Material 3 arayüz, harita ve grup yönetimi
    └── res/
        ├── drawable/                        # Özel vektör ikonlar ve harita işaretçileri
        ├── layout/activity_main.xml         # CoordinatorLayout + BottomSheet arayüzü
        └── values/                          # Koyu taktik tema, renkler ve metinler
```
