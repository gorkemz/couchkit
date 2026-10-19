# CouchKit

Yavaş ve reklamla dolu bir Android TV / Google TV'yi hızlı, sade bir salon ekranına çevirir:

- **CouchKit Cast**: AirPlay uyumlu gönderenler (iPhone, iPad, Mac) için ekran yansıtma ve medya
  alıcısı. TV açılınca kendiliğinden başlar, biri bağlanınca öne gelir.
  [android-airplay-server](https://github.com/jqssun/android-airplay-server) ve UxPlay tabanlıdır,
  lisansı GPL-3.0.
- **CouchKit Home**: hafif, cam görünümlü ana ekran. Öne çıkanlar şeridi, cam dock, ekran koruyucu,
  hava durumu, döviz ve canlı maç kartı var. Ekstraların hepsi isteğe bağlı ve sizin kendi API
  anahtarınızla çalışır. Lisansı Apache-2.0.
- **Kurulum betiği**: tek bir adb betiği. Önce cihazı kontrol eder, her değişikliği doğrular ve
  geri alma kaydı tutar.

English: [README.md](README.md)

> CouchKit'in Apple, Google, TCL ya da herhangi bir yayın servisiyle bağı yoktur. Ürün adları
> yalnızca uyumluluğu anlatmak için geçer ([TRADEMARKS.md](TRADEMARKS.md)). TV'nizde değişiklik
> yapmadan önce [DISCLAIMER.md](DISCLAIMER.md) dosyasını okuyun.

## Hangi cihazlar?

| Cihaz | Ne kurulur |
|---|---|
| **Doğrulanmış**: TCL Google TV "Smart TV Pro" (P735 serisi), Android 11, yazılım `V8-R51MT05-LF1V620` | her şey; stok ana ekranı kapatma ve gereksiz paketleri kapatma (debloat) dahil |
| Diğer TCL Android/Google TV'ler | uygulamalar, açılışta başlama düzeltmesi ve varsayılan ana ekran. Paket kapatmak için `--allow-unverified` gerekir. |
| Diğer Android TV'ler (SDK 26+) | güvenli mod: uygulamalar, izinler ve varsayılan ana ekran. Hiçbir paket kapatılmaz. |
| Telefon, tablet, TV olmayan cihazlar | reddedilir |

## Kendi başınıza kurulum (yaklaşık 10 dk)

1. **TV'de** şu adımları izleyin:
   - Ayarlar → Sistem → Hakkında → *Yapı numarası*'na 7 kez tıklayın.
   - Geliştirici seçenekleri → **USB hata ayıklama** / **Ağ üzerinden hata ayıklama**'yı açın.
   - TV'nin IP adresini not edin (Ayarlar → Ağ).
2. **Bilgisayarda** adb'yi kurun:
   - macOS: `brew install android-platform-tools`
   - Ubuntu: `sudo apt install adb`
   - Windows: WSL kullanın
3. **Uygulamaları edinin**: `couchkit-cast.apk` ve `couchkit-home.apk` dosyalarını Releases
   sayfasından `dist/` klasörüne indirin ya da `./scripts/build.sh` ile kendiniz derleyin.
4. **Önce önizleyin.** Bu komut hiçbir şeyi değiştirmez ve TV'ye bağlanmaz:
   ```
   ./install.sh --dry-run
   ```
5. **Kurun:**
   ```
   ./install.sh --host 192.168.x.y
   ```
   TV'de *Hata ayıklamaya izin verilsin mi?* sorusu çıkar. *Her zaman izin ver*'i işaretleyip
   Tamam'a basın. Betik her değişiklikten önce onay ister.

Ekstralar:

```
./install.sh --host 192.168.x.y --name "Salon TV" --config ayarlarim.json --screensaver
./install.sh --host 192.168.x.y --replace-stock-home   # ~300 MB RAM açar (doğrulanmış cihaz)
./install.sh --host 192.168.x.y --debloat=safe         # üretici telemetri/reklam paketleri
./install.sh --host 192.168.x.y --only receiver,autostart   # yalnız alıcı
```

Durum ve geri alma:

```
./install.sh --host 192.168.x.y --status   # salt okuma
./install.sh --host 192.168.x.y --undo     # kaydedilen her şeyi en yeniden başlayarak geri alır
./install.sh --host 192.168.x.y --rescue   # stok ana ekranı geri getirir
```

**Stok launcher ile kurulum sihirbazı neden birlikte kapatılıyor?** Doğrulanmış TV'de yalnız stok
launcher kapatılırsa bir sonraki açılışta Ev tuşunu kurulum sihirbazının kurtarma ekranı devralıyor
ve TV donmuş gibi görünüyor. Betik ikisinden birini asla tek başına kapatmaz. Kapatmadan önce
CouchKit Home'un ana ekran rolünde olduğunu kontrol eder, kapattıktan sonra doğrular, doğrulama
başarısız olursa hemen geri alır.

## Claude Code ile kurulum

Bu klasörü [Claude Code](https://claude.com/claude-code) ile açıp *"CouchKit'i TV'me kur"* deyin.
Claude [CLAUDE.md](CLAUDE.md) dosyasını okur ve TV'yi yalnızca okuyarak inceler. Cihazınıza uyan
adımları anlatır, onayınızdan sonra kurulumu adım adım yürütür.

## Ana ekran ayarları

[launcher/config.example.json](launcher/config.example.json) dosyasını `ayarlarim.json` adıyla
kopyalayın. İstediğiniz bölümleri bırakın, kendi anahtarlarınızı yazın ve `--config` ile verin.
Dosya verilmezse ana ekranda yalnızca uygulamalar, saat ve renk geçişli arka plan görünür.

## Lisanslar

- `receiver/` GPL-3.0'dır. Upstream'den değiştirilmiştir, değişiklikler
  [receiver/CHANGES.md](receiver/CHANGES.md) dosyasında.
- Geri kalan her şey Apache-2.0'dır ([LICENSE](LICENSE)).
- Üçüncü taraf bileşenler [THIRD_PARTY.md](THIRD_PARTY.md) dosyasında listelenir.
