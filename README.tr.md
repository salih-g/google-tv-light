# tv — yavaş bir Google TV'yi hiçbir şey kaybetmeden hızlandırmak

[English](README.md)

2 GB RAM'li bir Google TV (TCL 50C645, Android 11): uygulama açmak saniyeler sürüyor, her
geçişte her şey sıfırdan yükleniyordu. İki şey düzeltti; ikisi de geri alınabilir, hiçbir şey silinmedi:

1. **`tvclean.py`** – Wi-Fi üzerinden ADB ile çalışan küçük bir araç. Üreticinin reklam, telemetri
   ve "temizleyici" uygulamalarını gruplar hâlinde uyutur; dokunmayı reddettiği bir koruma listesi,
   her değişiklikten önce snapshot ve tek komutla geri alma vardır.
2. **`launcher/`** – 3,7 MB'lık Apple TV tarzı ana ekran: uygulamalarından oluşan bir dock, günün
   saatine göre değişen duvar kağıtları, Girişler/Wi-Fi/Ayarlar pilleri; içerik satırı yok, internet izni yok.

![TV'de ana ekran](docs/screenshot.jpg)

## Bu TV'de ne değişti

| | Önce | Sonra |
|---|---|---|
| Ana ekranda boşta boş RAM | 440 MB | 616 MB |
| Kullanılan swap | ~500 MB | ~110 MB |
| Ana ekran süreci | 333 MB (Google TV) | 36 MB |
| Açılışta kendini başlatan paket | 51 | 37 |
| RAM'de oturan TCL servisleri | ~300 MB | 0 |
| TCL "Safety Guard"ın Play Services'i öldürmesi | 5 dakikada 11 kez | yok |

Kullanılan her şey çalışmaya devam etti: HDMI/AV girişleri ve adları, Canlı TV, kumandanın
mikrofon, Kaynak ve Ayarlar tuşları, Chromecast, T-Cast ve Miracast yansıtma, USB medya,
Asistan, Play Store, sistem güncellemeleri.

## Güvenlik modeli

- Sistem paketleri asla kaldırılmaz. **Kullanıcı için devre dışı** bırakılır (`pm disable-user`):
  çalışmaz, yer kaplamaz; `pm enable` geri getirir. Fabrika ayarları da her şeyi geri getirir.
- [`protected.py`](protected.py), plan dosyası adını yazsa bile aracın **reddettiği** paketleri
  listeler: girişler, kumanda, yansıtma, Asistan, Play servisleri, klavyeler, güncelleme.
- Her değiştiren komut önce tüm paket durumlarının snapshot'ını `snapshots/`a yazar;
  `restore <snapshot>` TV'yi tam o duruma döndürür.
- Her değiştiren komut `--yes` eklenene kadar **dry run**'dır.
- "Uyut, kaldırma" kuralının tek istisnası: TCL Safety Guard `PERSISTENT` bir uygulamadır, sistem
  onu uykudayken bile başlatır; bu yüzden *yalnızca kullanıcı için* kaldırılır
  (`pm uninstall -k --user 0`); APK sistem bölümünde kalır, `readd` geri getirir.

Zor yoldan öğrenilip araca işlenen şeyler:

- Bir `pm` değişikliğinden birkaç saniye sonra yeniden başlatmak o değişikliği kaybettirir (paket
  yöneticisi tembel yazar). `tvclean.py reboot` önce bekler.
- adb-shell'in dosya gönderimi bu TV'nin adbd'sinde zaman aşımına uğrar; APK'lar shell üzerinden
  base64 parçalar hâlinde gider, md5 ile doğrulanır.
- Google TV'de `cmd package set-home-activity` tek başına üçüncü taraf bir ana ekrana Home tuşunu
  **veremez**: Android, tercihe bakmadan önce en yüksek öncelikli HOME etkinliğini seçer (Google'ın
  launcher'ı 2, kurulum sihirbazı 1, üçüncü taraf uygulamalar en fazla 0). Stok ana ekran ve
  kurulum sihirbazı uyumak zorunda. İkisi de tek komutla geri gelir.

## Gereksinimler

- macOS veya Linux, Python 3.10+ (`adb` programı gerekmez; Wi-Fi istemcisi saf Python).
- Launcher için: JDK 17 ve Android SDK komut satırı araçları, `platforms;android-34` ve
  `build-tools;34.0.0` (`build.sh` `ANDROID_HOME`'u okur, varsayılan Android Studio SDK yolu).
- Aynı Wi-Fi'da **USB hata ayıklama** açık bir Android TV / Google TV:
  Ayarlar → Sistem → Hakkında → *Derleme*'ye 7 kez OK → Geliştirici seçenekleri → USB hata ayıklama.

## Hızlı başlangıç

```bash
git clone <bu repo> tv && cd tv
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt

.venv/bin/python -I tvclean.py find        # TV'yi bulur, IP'yi .tv_host'a yazar; TV'deki izin sorusunu onayla
.venv/bin/python -I tvclean.py probe       # model, Android sürümü, RAM, Home'un sahibi
.venv/bin/python -I tvclean.py snapshot baseline
```

### 1. Grup grup debloat

[`plans/`](plans/) içindeki plan dosyaları TCL 50C645'e (R51MT05 firmware) uygulananlardır.
Başka TV'lerde paketler farklıdır: `tvclean.py shell 'pm list packages'` çalıştır,
[`plans/README.md`](plans/README.md) dosyasını oku ve kendi listelerini yaz. Sonra tek tek:

```bash
.venv/bin/python -I tvclean.py mem                               # önce
.venv/bin/python -I tvclean.py sleep plans/02-sleep-tcl-resident.txt        # dry run: komutları gösterir
.venv/bin/python -I tvclean.py sleep plans/02-sleep-tcl-resident.txt --yes  # uygula
```

Her gruptan sonra kumandayla test et: giriş değiştirme, mikrofon, Chromecast, bir yayın
uygulaması, ekran klavyesi. Bir şey bozulduysa: `wake <aynı plan> --yes`.

```bash
.venv/bin/python -I tvclean.py reboot --yes     # paket yöneticisini bekler, sonra yeniden başlatır
.venv/bin/python -I tvclean.py mem              # sonra
```

### 2. Launcher

[`launcher/res/values/config.xml`](launcher/res/values/config.xml) dosyasını düzenle: dock
uygulamaları, gizli uygulamalar, Canlı TV paketi, selamlamadaki adın. Duvar kağıtları
[`launcher/wallpapers/`](launcher/wallpapers/) altında `<sahne>-<dilim>.jpg` adıyla durur; dilimler
`sabah` (06–10), `gunduz` (10–17), `aksam` (17–20), `gece` (20–06); bir sahnenin hepsi olmak zorunda değil.

```bash
launcher/build.sh                                                   # -> launcher/build/tvhome.apk
.venv/bin/python -I tvclean.py install launcher/build/tvhome.apk \
    --grant=dev.salih.tvhome:android.permission.ACCESS_FINE_LOCATION --yes   # konum = yalnızca Wi-Fi adı için
.venv/bin/python -I tvclean.py shell 'am start -n dev.salih.tvhome/.MainActivity'   # dene; Home hâlâ Google'da
```

Beğenince Home tuşunu ver (Google TV'de ikinci satır tek başına yetmez):

```bash
.venv/bin/python -I tvclean.py sleep plans/08-sleep-stock-home.txt --yes
.venv/bin/python -I tvclean.py home set dev.salih.tvhome/.MainActivity --yes
```

Geri al: `tvclean.py wake plans/08-sleep-stock-home.txt --yes && tvclean.py home reset --yes`.

TV'de: D-pad gezinir, OK açar, **Aşağı** tüm uygulamalar ızgarasını ve girişler satırını açar,
**Geri** döner, **Menü** girişler menüsünü açar, bir kutuda **OK'ye uzun basmak** dock'u düzenler
ya da uygulamayı dock'a ekler. Kumandanın diğer her tuşu (ses, sessiz, mikrofon, Kaynak,
Ayarlar, kanal, uygulama kısayolları) sisteme bırakılır ve çalışmaya devam eder.

### 3. İsteğe bağlı: telemetriyi modemde kes

AdGuard Home ya da Pi-hole kullanıyorsan [`adguard/tcl-google-tv-rules.txt`](adguard/tcl-google-tv-rules.txt)
bu TV'nin konuştuğu TCL uç noktalarını içerir (promo push, sessiz uygulama kurulumu, launcher
içeriği). [Perflyst Smart-TV listesi](https://perflyst.github.io/PiHoleBlocklist/SmartTV.txt) iyi bir tamamlayıcı.

## Komutlar

| | |
|---|---|
| `find` / `probe` / `mem` / `snapshot [etiket]` | salt okunur |
| `sleep <plan>` / `wake <plan>` | paketleri kullanıcı için uyut / uyandır |
| `uninstall <plan>` | Play Store uygulamalarını kaldır (sistem paketlerini reddeder) |
| `remove <plan>` / `readd <plan>` | sistem paketini yalnızca kullanıcı için kaldır / geri getir |
| `install <apk> [--grant=pkg:izin]` | APK gönder ve kur |
| `home check` / `home set <bileşen>` / `home reset` | Home tuşu |
| `restore <snapshot.json>` | o snapshot'a dön |
| `reboot` | paket yöneticisi yazdıktan sonra yeniden başlat |
| `shell '<komut>'` | tek shell komutu |

Değiştiren tüm komutlar `--yes` ister.

## Test edilen cihaz

TCL 50C645 (Google TV, Android 11, Realtek RT51M, 2 GB RAM). `plans/` ve `protected.py`
içindeki paket adları bu TV'dendir; aracın kendisi USB hata ayıklaması açık her Android TV /
Google TV'de çalışır. Samsung (Tizen), LG (webOS) ve Fire TV için değildir.

## Lisanslar

Kod MIT ([LICENSE](LICENSE)). Duvar kağıtları ve ikonların kendi lisansları
[`launcher/wallpapers/CREDITS.md`](launcher/wallpapers/CREDITS.md) dosyasındadır.
[mihirgupta0900/tv-home](https://github.com/mihirgupta0900/tv-home) projesinden ilham alınmıştır.
