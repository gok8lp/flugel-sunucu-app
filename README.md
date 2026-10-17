# FLUGEL Sunucu — Android uygulaması ve yerel kontrol API'si

Kotlin/Jetpack Compose/Material 3, sabit koyu tema. Özet, konteyner, VM, masaüstü, HTTPS servis linkleri, bildirimler ve bağlantı ayarları ekranları bulunur. Uygulama adresi/tokenı koda gömülmez. Docker `destroy/rm/prune`, VM `destroy`, zorla kapatma ve serbest komut çalıştırma yoktur.

**Durum:** Kaynak ve API testleri hazır; yerel makinede Android SDK/Gradle kurulu olmadığından APK burada derlenmedi. GitHub Actions yapılandırması vardır ama bu depo GitHub'a gönderilmedi; CI çalıştırılmadı. Sunucu API'si kurulmadı, token oluşturulmadı, ağ/tünel değiştirilmedi. Claude kodu gözden geçirip dağıtımı ve yayını kullanıcı onayıyla yapacak.

## Android

- `app/`: yedi ekrana sahip Compose uygulaması. Sunucu URL'si HTTPS olmak zorunda; yönlendirme takip edilmez, sertifika doğrulaması Android'in varsayılan TLS denetimidir. Kontrol komutları onay diyaloğundan geçer. VM için sadece `start` ve ACPI `shutdown` görünür.
- Token Android Keystore AES-GCM ile saklanır, yedekleme kapalıdır. Resmî Android belgelerinde `EncryptedSharedPreferences` kullanımdan kaldırıldığı için yeni projede doğrudan Keystore kullanıldı. Telefon değişirse token tekrar girilir.
- Android Gradle Plugin 9.4'ün yerleşik Kotlin desteği kullanılır; ayrıca `kotlin-android` eklentisi uygulanmaz. Compose derleyici eklentisi AGP'nin varsayılan Kotlin 2.2.10 sürümüyle eşlidir.
- Yapılandırılmış Android SDK/JDK 17/Gradle 9.6 ile `gradle :app:assembleDebug` veya Android Studio'da Build çalıştırılır. Standart Gradle Wrapper henüz üretilmedi; `./gradlew` şu aşamada yoktur. APK çıktısı `app/build/outputs/apk/debug/` altında oluşur.
- `v*` etiketi gönderildiğinde CI debug APK ve **imzasız** release APK üretip GitHub Release oluşturacak şekilde hazırlanmıştır. Dağıtım öncesi imza ve cihaz testi zorunludur; etiketi yalnız kullanıcı kararıyla gönderin.

## Sunucu API — kurulum yalnız Claude tarafından

`server/api.py` yalnız Python standart kütüphanesini kullanır ve varsayılan olarak `127.0.0.1:8818` üzerinde dinler. Telefonun erişebilmesi için Claude'un TLS sonlandıran, erişimi sınırlı bir proxy veya Tailscale yolu kurması gerekir. API'yi doğrudan dış ağa bağlamayın. Mevcut Island JSON verisini yerel durum dosyasından okur; herkese açık Akış uç noktasına yeni kontrol yetkisi eklemez.

1. `server/api.py` ve `server/flugel-sunucu-app.service` gözden geçirilir. Birim örneği root çalışır, çünkü Docker, libvirt ve iki systemd servisini kontrol eder; bu geniş yetkiyi dar izin listesi ve loopback/TLS katmanlarıyla sınırlamak kritiktir.
2. `/etc/flugel-sunucu-app/config.json` dosyası [config.example.json](server/config.example.json) şablonundan hazırlanır. `containers` ve `vms` listeleri varsayılan boş bırakılmıştır; yalnız gerçekten izin verilen adlar eklenir. `links` yalnız HTTPS URL kabul eder.
3. Token dosyası `/etc/flugel-sunucu-app/token` için rastgele en az 32 ASCII karakter oluşturulur, mod `0600` ve root sahipliği verilir. Token depoya, komut satırı argümanına, loga veya ekran görüntüsüne konmaz; kullanıcı uygulamanın Ayarlar ekranına kendisi girer.
4. Kod `/opt/flugel-sunucu-app/api.py` konumuna, systemd birimi uygun yere kopyalanır; servis etkinleştirmeden önce testler, izinler ve proxy politikası doğrulanır. API yolları: `GET /v1/status|containers|vms|desktops|links|notifications` ve izin listeli `POST /v1/containers/<ad>/start|stop|restart`, `/v1/vms/<ad>/start|shutdown`, `/v1/desktops/webtop|kasm/start`.

## Test ve güvenlik notları

```bash
python3 -m unittest discover -s server -v
python3 -m py_compile server/api.py server/test_api.py
```

API, `Authorization: Bearer` tokenı sabit zamanlı karşılaştırır; token dosyası yalnız sahibi tarafından okunabilir olmalıdır. Komutlar sabit argüman listesiyle ve zaman aşımıyla çalışır; shell kullanılmaz. İşlem sırasında tek bir eylem yürütülür, başarı veya genel hata döner; ham komut hata çıktısı ve token loglanmaz. Proxy katmanında ayrıca TLS, yetkili cihaz erişimi, hız sınırı ve log/denetim politikası gerekir. Mevcut `flugel-webtop`/`flugel-kasm` karşılıklı dışlama kuralları serviste kalır; API yalnız `systemctl start` çağırır.

Bu proje henüz yayımlanmadı. APK kurulumu veya API dağıtımı için sunucu ve telefon üzerinde ayrıca güvenlik incelemesi gerekir.
