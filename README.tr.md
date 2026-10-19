<p align="center"><img src="docs/images/banner.png" alt="CouchKit" width="100%"></p>

<p align="center">
  <a href="LICENSE"><img alt="Apache-2.0" src="https://img.shields.io/badge/launcher%20%2B%20kurulum-Apache--2.0-blue"></a>
  <a href="receiver/LICENSE"><img alt="GPL-3.0" src="https://img.shields.io/badge/al%C4%B1c%C4%B1-GPL--3.0-blue"></a>
  <img alt="Android TV 8+" src="https://img.shields.io/badge/Android%20TV-8.0%2B-3ddc84">
  <a href="README.md"><img alt="English" src="https://img.shields.io/badge/lang-English-blue"></a>
</p>

**CouchKit, yavaş ve reklamla dolu bir Android TV / Google TV'yi hızlı ve sade bir salon ekranına
çevirir.** Üç parçadan oluşur; her biri tek başına da kurulabilir:

| | |
|---|---|
| 📺 **CouchKit Cast** | iPhone, iPad ya da Mac ekranını TV'ye yansıtır (AirPlay uyumlu gönderenlerle çalışır). TV açılınca kendiliğinden başlar, biri bağlanınca öne gelir. |
| 🛋️ **CouchKit Home** | Hafif, cam görünümlü bir ana ekran. İsteğe bağlı ekstraları var: hava durumu, döviz, canlı maç kartı, öne çıkanlar şeridi ve ekran koruyucu. |
| 🧰 **Kurulum betiği** | TV'yi tanır, her değişiklikten önce sorar ve her değişikliği doğrular. Yaptığı her şeyi geri alabilir. |

<table>
<tr>
<td width="50%"><img src="docs/images/home.png" alt="Ana ekran"><br><sub><b>Ana ekran</b>: hava durumu ve döviz</sub></td>
<td width="50%"><img src="docs/images/screensaver.png" alt="Ekran koruyucu"><br><sub><b>Ekran koruyucu</b>: kendi foto ve videolarınız ya da hareketli renk geçişi</sub></td>
</tr>
<tr>
<td width="50%"><img src="docs/images/receiver.png" alt="Alıcı"><br><sub><b>Alıcı</b> bağlantı bekliyor</sub></td>
<td width="50%"><img src="docs/images/installer-dry-run.png" alt="Kurulum"><br><sub><b>Kurulum</b> önizlemesi (<code>--dry-run</code>)</sub></td>
</tr>
</table>

## Hızlı başlangıç

> **İlk kez mi yapıyorsunuz?** **[Adım adım kılavuz](docs/KILAVUZ.md)** her şeyi ekran
> görüntüleriyle anlatır: TV'de geliştirici seçeneklerini açmaktan ilk ekran yansıtmaya kadar.

1. TV'de **Geliştirici seçenekleri → USB hata ayıklama**'yı açın ve TV'nin IP adresini not edin
   ([nasıl?](docs/KILAVUZ.md#1-tvde-geliştirici-seçeneklerini-açın)).
2. Bilgisayara adb kurun:
   - macOS: `brew install android-platform-tools`
   - Linux: `sudo apt install adb`
   - Windows: [kılavuz](docs/KILAVUZ.md#4-bilgisayara-adb-kurun)
3. Bu repoyu indirin. **Releases**'tan `couchkit-cast.apk` ve `couchkit-home.apk` dosyalarını
   `dist/` klasörüne koyun ya da `./scripts/build.sh` ile kendiniz derleyin.
4. Önce önizleyin, sonra kurun:
   ```bash
   ./install.sh --dry-run
   ./install.sh --host 192.168.1.23
   ```
   TV'de *USB hata ayıklamaya izin verilsin mi?* sorusunu onaylayın. *Play Protect* uyarısı çıkarsa
   *Yine de yükle*'yi seçin.

İstediğiniz an geri alın:

```bash
./install.sh --host 192.168.1.23 --undo     # betiğin kaydettiği her şeyi geri alır
./install.sh --host 192.168.1.23 --rescue   # stok ana ekranı geri getirir
```

### Ya da Claude kursun

Bu klasörü [Claude Code](https://claude.com/claude-code) ile açıp *"CouchKit'i TV'me kur"* deyin.
Claude [CLAUDE.md](CLAUDE.md)'deki kurallara uyar:
- önce TV'yi yalnızca okuyarak inceler
- cihazınıza uyan adımları anlatır
- her adımı ayrı ayrı onayınızı aldıktan sonra yürütür

## Hangi TV'ler?

| Cihaz | Ne kurulur |
|---|---|
| ✅ **Doğrulanmış**: TCL Google TV "Smart TV Pro" (P735 serisi), Android 11, yazılım `V8-R51MT05-LF1V620` | her şey; stok ana ekranı kapatma (~300 MB RAM kazanılır) ve debloat dahil |
| 🟡 Diğer TCL Android / Google TV'ler | uygulamalar, açılışta başlama düzeltmesi ve varsayılan ana ekran. Paket kapatmak için `--allow-unverified` gerekir. |
| 🟢 Diğer Android TV'ler (Android 8+) | güvenli mod: uygulamalar, izinler ve varsayılan ana ekran. Hiçbir paket kapatılmaz. |
| ⛔ Telefon, tablet, TV olmayan cihazlar | reddedilir |

## Ana ekran ekstraları

Her ekstra siz `config.json`'da açana kadar **kapalıdır**; anahtarlar sizin kendi hesaplarınızdır.
[launcher/config.example.json](launcher/config.example.json) dosyasını kopyalayın, istediklerinizi
bırakın ve `--config` ile kurun.

| Ekstra | Kaynak | Anahtar |
|---|---|---|
| Hava durumu | Open-Meteo | yok |
| Döviz | Frankfurter (ECB) | yok |
| Öne çıkanlar şeridi | TMDB | kendi ücretsiz anahtarınız |
| Canlı maç + gol bildirimi | API-Football | kendi ücretsiz anahtarınız (günde 100 istek) |
| Ekran koruyucu | `Android/data/io.couchkit.home/files/screensaver/` klasöründeki kendi foto ve videolarınız | yok |

## Hukuki

- **Lisanslar:**
  - `receiver/` **GPL-3.0**'dır. [android-airplay-server](https://github.com/jqssun/android-airplay-server)
    (UxPlay tabanlı) üzerinde değişiklik yapılmış halidir; değişiklikler
    [receiver/CHANGES.md](receiver/CHANGES.md) dosyasında.
  - Geri kalan her şey **Apache-2.0**'dır.
- **Bağımsızlık:** CouchKit'in Apple, Google, TCL ya da herhangi bir yayın servisiyle bağı yoktur.
  Ürün adları yalnızca uyumluluğu anlatmak için geçer ([TRADEMARKS.md](TRADEMARKS.md)).
- **Garanti yok:** TV'nizin sistem ayarlarını değiştirir. [DISCLAIMER.md](DISCLAIMER.md) dosyasını okuyun.
