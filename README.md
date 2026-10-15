# OffGridTrack - İnternetsiz Canlı Konum Paylaşımı (Android)

Bu uygulama, hücresel internet veya Wi-Fi bağlantısı olmadan, doğrudan uydu GPS'i ve şifreli SMS protokolü kullanarak iki veya daha fazla Android cihaz arasında mesafe sınırı olmaksızın güvenli, uçtan uca şifrelenmiş canlı konum takibi sağlar.

---

## 🚀 Temel Özellikler ve Mimari

| Bileşen | Kullanılan Teknoloji | Görevi |
| :--- | :--- | :--- |
| **Konum Motoru** | `Android LocationManager` (`GPS_PROVIDER`) | Google Play Hizmetleri ve internete ihtiyaç duymadan doğrudan uydudan koordinat, hız ve açı bilgisi alır. |
| **Güvenlik & Eşleşme** | `AES/GCM/NoPadding` (256-bit) | Eşleşen cihazlar arasında belirlenen ortak parola (Grup Anahtarı) ile koordinatları uçtan uca şifreler. Operatör veya 3. şahıslar konumu göremez. |
| **İletişim Katmanı** | `SmsManager` & `BroadcastReceiver` | Mesafe sınırı olmaksızın şifreli konum paketlerini (`OGT1:...`) arka planda SMS olarak iletir ve gelen paketleri otomatik yakalar. |
| **Harita Motoru** | `OSMDroid` (OpenStreetMap) | Çevrimdışı harita verilerini okur, kullanıcının ve eşleşen kişinin canlı konumunu ve aradaki mesafeyi gösterir. |

---

## 📁 Proje Dizin Yapısı

```text
OffGridTrack/
├── .github/
│   └── workflows/
│       └── build-apk.yml               # GitHub Actions ile otomatik APK derleme
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/
│   │       │   └── com/
│   │       │       └── offgrid/
│   │       │           └── tracker/
│   │       │               ├── CryptoHelper.kt          # AES-256 GCM Şifreleme / Çözme
│   │       │               ├── LocationSmsReceiver.kt   # Gelen şifreli SMS paket yakalayıcı
│   │       │               └── MainActivity.kt          # UI, OSMDroid harita ve GPS takibi
│   │       ├── res/
│   │       │   └── values/
│   │       │       ├── strings.xml
│   │       │       └── themes.xml
│   │       └── AndroidManifest.xml                      # İzinler ve servis bildirimleri
│   └── build.gradle.kts                                 # Modül seviyesi bağımlılıklar
├── build.gradle.kts                                     # Proje seviyesi derleme ayarları
├── settings.gradle.kts                                  # Modül yapılandırması
├── gradle.properties
└── README.md
```

---

## ⚙️ Kurulum ve APK Derleme Yöntemleri

### Yöntem 1: GitHub Actions ile Bulutta Otomatik APK Üretme (Önerilen)
Bilgisayarınıza Android Studio veya SDK kurmadan doğrudan APK elde etmek için:
1. Bu projeyi bir GitHub reposuna yükleyin (`git push`).
2. GitHub reponuzun **Actions** sekmesine gidin.
3. **Build OffGridTrack APK** iş akışının tamamlanmasını bekleyin (~2-3 dakika).
4. Tamamlandığında sayfanın altındaki **Artifacts** bölümünden `OffGridTrack-APK` dosyasını indirin.

### Yöntem 2: Android Studio ile Yerel Derleme
1. Android Studio'yu açın ve `OffGridTrack` klasörünü proje olarak içeri aktarın (**Open**).
2. Gradle senkronizasyonunun tamamlanmasını bekleyin.
3. Menüden **Build > Build Bundle(s) / APK(s) > Build APK(s)** seçeneğine tıklayın.
4. Üretilen `app-debug.apk` dosyasını test edeceğiniz Android cihazlara aktarın.

---

## 📱 Kullanım Adımları

1. İki Android cihaza da üretilen APK'yı yükleyin.
2. Uygulama açılışında istenen **Konum** ve **SMS** izinlerini onaylayın.
3. Her iki cihazda da **Ortak Şifreleme Anahtarı** alanına aynı parolayı girin (Örn: `Kamp2026`).
4. **Eşleşecek Telefon No** alanına diğer cihazın telefon numarasını girin (Örn: `+905xxxxxxxxx`).
5. **Canlı Konum Paylaşımını Başlat (İnternetsiz)** anahtarını aktif hale getirin.
6. Cihazlar hareket ettikçe arka planda şifreli SMS paketleri iletilecek, harita üzerinde eşleşen kişinin konumu, hareket hızı ve aradaki kuş uçuşu mesafe canlı olarak güncellenecektir.
