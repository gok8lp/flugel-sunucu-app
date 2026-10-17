# FLUGEL Sunucu — Android uygulaması ve yerel kontrol API'si

Kotlin/Jetpack Compose/Material 3, sabit koyu tema. Özet, konteyner, VM, masaüstü, HTTPS servis linkleri, bildirimler ve bağlantı ayarları ekranları bulunur. Uygulama adresi/tokenı koda gömülmez. Docker `destroy/rm/prune`, VM `destroy`, zorla kapatma ve serbest komut çalıştırma yoktur.

**Durum:** Android test APK'si yerelde derlendi; Android Lint ve Python API testleri geçti. İlk dağıtım yalnız telefon üzerinde deneme içindir; cihaz testi henüz yapılmadı. Sunucu API'si kurulmadı, token oluşturulmadı, ağ/tünel değiştirilmedi. Dolayısıyla uygulama açılır fakat Ayarlar'a canlı HTTPS adresi ve token girilene kadar sunucu verisi göstermez.

## Android

- `app/`: yedi ekrana sahip Compose uygulaması. Sunucu URL'si HTTPS olmak zorunda; yönlendirme takip edilmez, sertifika doğrulaması Android'in varsayılan TLS denetimidir. Kontrol komutları onay diyaloğundan geçer. VM için sadece `start` ve ACPI `shutdown` görünür.
- Token Android Keystore AES-GCM ile saklanır, yedekleme kapalıdır. Resmî Android belgelerinde `EncryptedSharedPreferences` kullanımdan kaldırıldığı için yeni projede doğrudan Keystore kullanıldı. Telefon değişirse token tekrar girilir.
- Android Gradle Plugin 9.4'ün yerleşik Kotlin desteği kullanılır; ayrıca `kotlin-android` eklentisi uygulanmaz. Compose derleyici eklentisi AGP'nin varsayılan Kotlin 2.2.10 sürümüyle eşlidir.
- Android SDK platform 37.0, Build Tools 37.0.0, JDK 21 ve Gradle 9.6 ile `gradle :app:assembleDebug :app:lintDebug` çalıştırılır. `targetSdk` hâlâ 36'dır; 37 yalnız güncel Compose bağımlılıklarını derlemek için kullanılır. Gradle Wrapper henüz üretilmedi; `./gradlew` şu aşamada yoktur. APK çıktısı `app/build/outputs/apk/debug/` altında oluşur.
- GitHub Actions yalnız test APK'sini derler ve artifact olarak yükler; otomatik GitHub Release veya imzasız production APK yayımlamaz. Deneme sürümü Android'in debug anahtarıyla imzalıdır. Üretim sürümü için kalıcı özel imzalama anahtarı, güvenli saklama ve gerçek cihaz testi gerekir. İleride farklı anahtarla imzalanan uygulamaya geçerken mevcut debug uygulamasını kaldırmak gerekebilir; bu işlem yerel ayarları siler.

## Sunucu API — kurulum yalnız Claude tarafından

`server/api.py` yalnız Python standart kütüphanesini kullanır ve varsayılan olarak `127.0.0.1:8818` üzerinde dinler. Claude telefon erişimi için tailnet içi HTTPS `tailscale serve` kuracak; **Funnel ve herkese açık uç nokta yok**. Uygulamada varsayılan adres boş kalır, kullanıcı girer. API'yi doğrudan dış ağa bağlamayın. Mevcut Island JSON verisini yerel durum dosyasından, bildirimleri `bildirimler.log` dosyasının son 80 satırından okur; herkese açık Akış uç noktasına yeni kontrol yetkisi eklemez.

1. `server/api.py` ve `server/flugel-sunucu-app.service` gözden geçirilir. Birim `flugel` kullanıcısı ve `docker`/`libvirt` ek gruplarıyla çalışır; bu gruplar (özellikle Docker soketi) geniş yetki verir, dolayısıyla Bearer token, erişim kısıtı, izin listesi ve tailnet TLS güvenlik incelemesi zorunludur. `ProtectSystem=strict` ve `ProtectHome=read-only` korunur.
2. `/etc/flugel-sunucu-app/config.json` dosyası [config.example.json](server/config.example.json) şablonundan hazırlanır. `containers` ve `vms` listeleri varsayılan boş bırakılmıştır; yalnız gerçekten izin verilen adlar eklenir. `links` yalnız HTTPS URL kabul eder.
3. Token dosyası `/etc/flugel-sunucu-app/token` için rastgele en az 32 ASCII karakter oluşturulur, mod `0600` ve `flugel` sahipliği verilir; yapılandırma ve Island/bildirim durum dosyalarının da bu kullanıcı tarafından okunabildiği doğrulanır. Token depoya, komut satırı argümanına, loga veya ekran görüntüsüne konmaz; kullanıcı uygulamanın Ayarlar ekranına kendisi girer.
4. Kod `/opt/flugel-sunucu-app/api.py` konumuna, systemd birimi uygun yere kopyalanır; servis etkinleştirmeden önce testler, izinler ve tailnet-only `tailscale serve` politikası doğrulanır. Masaüstü başlatma mevcut anahtar API'ye `POST` yapar; karşılıklı `Conflicts` kuralı oradadır. Bu iç API'nin ayrıca kimlik doğrulaması yoktur ve Docker köprüsünden doğrudan erişimi güvenlik incelemesi ister. API yolları: `GET /v1/status|containers|vms|desktops|links|notifications` ve izin listeli `POST /v1/containers/<ad>/start|stop|restart`, `/v1/vms/<ad>/start|shutdown`, `/v1/desktops/webtop|kasm/start`.

## Test ve güvenlik notları

```bash
python3 -m unittest discover -s server -v
python3 -m py_compile server/api.py server/test_api.py
```

API, `Authorization: Bearer` tokenı sabit zamanlı karşılaştırır; token dosyası yalnız sahibi tarafından okunabilir olmalıdır. Docker/libvirt komutları sabit argüman listesiyle ve zaman aşımıyla çalışır; shell kullanılmaz. İşlem sırasında tek bir eylem yürütülür, başarı veya genel hata döner; ham komut hata çıktısı ve token loglanmaz. Tailscale Serve katmanında ayrıca tailnet cihaz erişimi, hız sınırı ve log/denetim politikası gözden geçirilmelidir. Mevcut `flugel-webtop`/`flugel-kasm` karşılıklı dışlama kuralları serviste kalır; API masaüstü için yalnız anahtar API'ye POST çağırır.

APK'nin dağıtılması sunucu API'sini dağıtmaz. Telefon işlevlerini uçtan uca kullanmak için sunucu API'si, tailnet HTTPS ve erişim sınırlarının ayrıca güvenlik incelemesinden geçirilip kurulması gerekir. Deneme APK'sini yalnız bu deponun GitHub Release sayfasından indir; üçüncü taraf kopyalara güvenme.
