# Disclaimer / Sorumluluk reddi

**English.** This software is provided "AS IS", WITHOUT WARRANTY OF ANY KIND (see LICENSE and
receiver/LICENSE). It changes system settings of your TV over adb: it installs apps, grants
permissions, changes the default home screen and - only if you ask for it - disables
pre-installed packages. A wrong change can make the TV boot to a black or recovery screen.

- Every change is recorded in an undo journal on your computer: `./install.sh --host IP --undo`.
- If the stock home screen is gone and no journal exists: `./install.sh --host IP --rescue`.
- Last resort: factory reset from the TV's settings or recovery menu (erases all data on the TV).
- Steps that disable system packages run only on devices listed in installer/verified-devices.txt
  unless you explicitly pass `--allow-unverified`.
- You are responsible for complying with the terms of service of your TV, of the apps you use, and
  of any data service you configure (TMDB, API-Football, Open-Meteo, Frankfurter).
- Modifying your TV may affect its warranty or support.

**Türkçe.** Bu yazılım "OLDUĞU GİBİ", HİÇBİR GARANTİ OLMADAN sunulur. TV'nizin sistem ayarlarını
adb üzerinden değiştirir: uygulama kurar, izin verir, varsayılan ana ekranı değiştirir ve yalnızca
siz isterseniz hazır gelen paketleri devre dışı bırakır. Yanlış bir değişiklik TV'nin siyah ekranda
ya da kurtarma ekranında açılmasına yol açabilir.

- Her değişiklik bilgisayarınızda bir geri alma kaydına yazılır: `./install.sh --host IP --undo`.
- Ana ekran kaybolduysa ve kayıt yoksa: `./install.sh --host IP --rescue`.
- Son çare: TV ayarlarından ya da kurtarma menüsünden fabrika ayarlarına dönmek (TV'deki tüm
  veriler silinir).
- Sistem paketi kapatan adımlar yalnızca installer/verified-devices.txt'deki cihazlarda çalışır;
  başka cihazlarda `--allow-unverified` gerekir.
- TV'nizin, kullandığınız uygulamaların ve ayarladığınız veri servislerinin kullanım koşullarına
  uymak sizin sorumluluğunuzdadır. TV'de değişiklik yapmak garantiyi etkileyebilir.
