# Home Server Android

Kişisel ev sunucusu için Kotlin/Jetpack Compose Android istemcisi ve Python kontrol API'si prototipi.
Android dashboard and a local Python API for a personal home server.

## Ne var?

- Sunucu özeti, Docker konteynerleri, sanal makineler, masaüstleri, servis bağlantıları ve bildirim ekranları.
- HTTPS adresi ve erişim tokenı kullanıcı tarafından girilir; kimlik bilgileri kaynakta hazır değildir.
- Android Keystore ile yerel token saklama.
- Python standart kütüphanesiyle API ve izin listesine bağlı start/stop işlemleri.

Bu uygulama genel amaçlı SSH istemcisi değildir. API kurulmadan uygulama sunucu verisi göstermez.
Test APK'sinin bulunması fiziksel cihazda bütün işlevlerin doğrulandığı anlamına gelmez.

## Dizinler

| Dizin | İçerik |
| --- | --- |
| `app/` | Android ekranları, istemci ve yerel güvenli saklama |
| `server/api.py` | Yerel HTTP API |
| `server/config.example.json` | İzin verilen konteyner/VM listeleri için örnek |
| `server/test_api.py` | API testleri |
| `.github/workflows/android.yml` | Debug APK ve lint iş akışı |

## Android derlemesi

Projede Gradle Wrapper yoktur; sistemde uygun Gradle ve Android SDK kurulmalıdır.
CI yapılandırması Gradle 9.6.0, Android 37.0 platform/build tools ve Java kullanır;
kesin ayarlar build dosyalarında ve workflow içinde görülebilir.

```bash
gradle :app:assembleDebug :app:lintDebug
```

APK: `app/build/outputs/apk/debug/`. Hazır test paketleri varsa [Releases](https://github.com/gok8lp/home-server-android/releases) sayfasındadır.
Bu belgeler güncellendiğinde eski APK'lerin görünen adı otomatik değişmez.

## API'yi önce test et

```bash
python3 -m unittest discover -s server -v
```

Kurulumdan önce `server/config.example.json`, `server/api.py` ve systemd birimini incele.
API varsayılan `127.0.0.1:8818` üzerinde çalışır; Android erişimi için yetkilendirilmiş HTTPS katmanı gerekir.
Konteyner/VM izin listelerini kendi ortamına göre doldur; API'yi kimlik doğrulamasız internete açma.
Docker/libvirt yetkileri ve token dosyası izinleri kurulumun parçasıdır. Bu depo değişikliği sunucuda kurulum yapmaz.

## İlgili projeler ve eski teknik adlar

[home-server](https://github.com/gok8lp/home-server): altyapı örnekleri.
[desktop-server-toolkit](https://github.com/gok8lp/desktop-server-toolkit): masaüstü ve veri yardımcıları.
Android paket kimliği, servis dosyası ve yapılandırma yolları mevcut kurulumlarla uyumluluk için korunur.
