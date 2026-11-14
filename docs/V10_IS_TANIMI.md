# Finn's Watch v0.10: iş tanımı

Üç özellik: son görülen yer, saatteki kadranı saatten öğrenmek, sağlık verisini dışa aktarmak.
Bu belge uygulayacak kişi ya da ajan için tek başına yeterli olacak şekilde yazıldı.

## 0. Proje bağlamı

- **Repo:** github.com/etiyar/ls05-controller. Uygulama paketi `com.che.ls05`, adı "Finn's Watch". Hedef saat Haylou Solar LS05 (UTE/Youcy BLE protokolü, 240x240 yuvarlak ekran).
- **Yapı:** Yalnızca Android framework View'ları kullanılıyor. AndroidX, Compose ve coroutine yok, eklenmeyecek.
  - minSdk 26, targetSdk 35.
  - `protocol/` modülü saf Kotlin; Android'e bağlı değil, birim testleri burada.
  - `app/` Android tarafı.
- **Ana dosyalar** (`app/src/main/java/com/che/ls05/`):
  - `WatchService.kt`: önplan servisi, BLE oturumu, geçmiş okuma.
  - `Ls05Ble.kt`: GATT kuyruğu, ham çerçeveler.
  - `WatchHub.kt`: durum ve gözlemciler.
  - `Prefs.kt`: ayarlar.
  - `Pages.kt`: 4 sayfa, `WatchPage` içinde "Kadranlarım".
  - `Ui.kt`: ortak bileşenler.
  - `LinkAlert.kt`: kayıp saat uyarısı.
  - `FaceLibrary.kt`: kadran galerisi.
  - `FaceUpload.kt`: kadran yükleme.
  - `HealthStore.kt`: günlük ham kayıtlar ve `view()`.
- **Protokol notları:** `docs/PLAN.md`.
- **Derleme:** GitHub Actions ("APK derle") her dalda derler ve `protocol` testlerini çalıştırır. Telefona yayın (build-N) yalnızca `main` dalından çıkar. İş ayrı bir dalda yapılır ve PR açılır; birleştirmeyi proje sahibi yapar.
- **Kurallar (bozulmaması gerekenler):**
  1. Saate doğrulanmamış komut otomatik gönderilmez. Yeni bir komut önce yalnızca elle (Gelişmiş bölümündeki bir düğmeyle) denenir.
  2. Kişisel veri loga ya da repoya yazılmaz: MAC, kullanıcı kimliği, bildirim içeriği, şehir, konum, sağlık değerleri.
  3. Kod, yorum ve commit mesajlarında yapay zekâ izi bırakılmaz.
  4. Arayüz metinleri Türkçe; kısa ve sade cümleler. Mevcut `Ui` yardımcıları ve renk tokenları kullanılır, koda sabit renk yazılmaz.
  5. Her yeni saf mantık `protocol/` modülünde birim testiyle gelir.

## 1. Son görülen yer

**Amaç:** Kayıp saat uyarısı geldiğinde saatin nerede kaldığını göstermek. Bağlantı beklenmedik şekilde koptuğu anda telefonun konumu kaydedilir.

**Davranış**
- **Ayar:** Ayarlar > Saat, "Saat uzaklaşınca uyar" satırının altında "Son görülen yeri kaydet". Varsayılan kapalı.
  - Açılınca konum izni istenir: önce `ACCESS_COARSE_LOCATION`, kullanıcı isterse kesin konum.
  - İzin verilmezse anahtar kapalı kalır ve kısa bir açıklama gösterilir.
- **Kayıt anı:** `LinkAlert`'in "beklenmedik kopma" kararı verdiği an. Kullanıcı kesmesi, Bluetooth kapatma ve servis durdurma kayıt başlatmaz.
- **Konum alma:**
  - API 30 ve üstünde `LocationManager.getCurrentLocation(...)` en çok 10 sn beklenir.
  - Gelmezse ya da API daha eskiyse `getLastKnownLocation` ile NETWORK, PASSIVE ve GPS sağlayıcılarının en yenisi kullanılır, ama yalnızca son 5 dakika içindeyse.
- **Saklanan:** Yalnızca son kayıt (enlem, boylam, doğruluk, zaman) `Prefs` içinde. Geçmiş tutulmaz, loga yazılmaz.
- **Gösterim:**
  - Uyarı bildirimine metin eklenir: "Son görülen: 14:32 (±40 m)".
  - Bildirime "Haritada aç" eylemi eklenir: `geo:lat,lon?q=lat,lon(Saat)` intent'i.
  - Ayarlar'da da son kayıt bir satırla gösterilir, dokununca harita açılır, yanında "Sil" düğmesi olur.
- Saat yeniden bağlanınca kayıt silinmez; ayar kapatılınca silinir.

**Teknik risk (önemli):** Servis arka planda çalışıyor. Android 14+ arka planda konuma erişimi kısıtlıyor.
- Servis türüne `location` eklenmeli:
  - Manifestte `foregroundServiceType="connectedDevice|location"`.
  - Manifeste `FOREGROUND_SERVICE_LOCATION` izni eklenmeli.
  - `startForeground(id, n, types)` çağrısında `FOREGROUND_SERVICE_TYPE_LOCATION`, **yalnızca konum izni o an verilmişse** eklenmeli. İzinsiz eklenirse servis `SecurityException` ile çöker.
  - Bugünkü çağrı: `WatchService.kt` içinde `startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)`.
- İzin sonradan verilirse servisin yeniden başlatılması gerekir; kullanıcıya "Servis yeniden başlatıldı" denir.
- `ACCESS_BACKGROUND_LOCATION` istenmez. Önplan servisi türü yeterli olmalı; değilse bu açıkça raporlanır.

**Test**
- Saf mantık (`protocol`):
  - Konum seçimi: en yeni ve yeterince taze olan konum.
  - "Son görülen" metninin biçimi.
- Telefonda:
  - İzin verilmeden açma denemesi.
  - Ekran kapalıyken kopma: bildirimde konum ve harita eylemi görünmeli.
  - Bluetooth kapatınca kayıt yapılmamalı.

## 2. Saatteki kadranı saatten öğrenmek

**Amaç:** Galerideki "Saatte" işaretini, uygulamanın kendi kaydı yerine saatin cevabından almak.

**Bilinen**
- Komut `1a 01` (kadran yapılandırma sorgusu). Yanıtı: `1a 01 [kimlik u32 BE] [en u16 BE] [boy u16 BE] 00 00 10 00 00`.
  - Kimlik `ff ff ff ff` ise saatte yerleşik kadran var.
  - Yüklenen dosyanın kimliği, dosya başlığının ilk 2 baytı (u16 LE). Örnek: resmi Haylou kadranı 5002 = `13 8a`, yanıtta `00 00 13 8a`.
- Bu komut bugüne kadar **yalnızca yükleme akışının başında**, ardından `1a 02` gelerek gönderildi (`FaceUpload.kt`). Tek başına gönderilmesi denenmedi. Kural 1 bu yüzden geçerli.

**Aşama A (zorunlu, önce bu):**
- Ayarlar > Gelişmiş'e "Kadranı sor (1a 01)" düğmesi eklenir. Düğme yalnızca `1a 01` gönderir ve yanıtı loga yazar: kimlik onaltılık, en/boy.
- Proje sahibi bunu telefonda dener:
  - Saat normal çalışmaya devam ediyor mu?
  - Ekranda bir şey değişiyor mu?
  - Yanıt yukarıdaki biçimde mi?
- Bu sonuç PR açıklamasına yazılır.

**Aşama B (A sorunsuzsa; aynı PR'da bayrak arkasında olabilir):**
- **Ne zaman sorulur:** Bağlantı hazır olunca bir kez, ve kadran yüklemesi bittikten sonra. Kadran yüklemesi sürerken hiç sorulmaz (`FaceUpload.active`).
- **Kimlik eşleme:**
  - Yanıttaki kimlik, galerideki dosyaların başlık kimliğiyle karşılaştırılır.
  - **Dikkat:** Birden fazla dosya aynı kimliği taşıyabilir. Örneğin `tools/face_builder.py` ile üretilen FW kadranları resmi dosyanın kimliğini (5002) koruyor. Bu durumda en son yüklenen kayıt (`Prefs.currentFace`) eşleşiyorsa o işaretlenir; eşleşmiyorsa hiçbiri işaretlenmez ve kartta "Saatteki kadran galeride yok" yazar.
  - `ff ff ff ff`: hiçbiri işaretlenmez; kartta "Saatte yerleşik kadran" yazar.
- `WatchHub`'a `faceOnWatchId: Long?` eklenir. `WatchPage.refreshFaces()` bunu kullanır.
- **Ek iyileştirme:** `tools/face_builder.py` her kadrana ayrı kimlik verebilsin (`--id`), varsayılan 0xF100'den başlasın. `FaceWriter` ile derlenen .tbwf'ler zaten 0xF000–0xFFFE aralığında benzersiz.

**Test**
- `protocol`: yanıt ayrıştırma (`FaceConfig` genişletilir: kimlik, en, boy) ve eşleme kuralı.
- Telefonda:
  - Haylou Fun ya da saatin menüsünden kadran değiştirip uygulamayı açınca işaretin doğru olması.

## 3. Sağlık verisini dışa aktarma

**Amaç:** Adım, nabız ve uyku verisini Excel'de açılabilecek dosyalara aktarmak.

**Davranış**
- Bugün sayfasının altında, "Geçmiş" kartından sonra "Verileri dışa aktar" düğmesi. Basınca aralık sorulur: "Son 7 gün", "Son 30 gün", "Tümü (90 gün)".
- **Dosya kaydetme:** `Intent.ACTION_CREATE_DOCUMENT` ile kullanıcı yeri seçer. FileProvider (AndroidX) kullanılmaz.
- **Dosya:** Tek bir `.zip`, adı `finns-watch-saglik-YYYYAAGG.zip`. İçinde üç UTF-8 CSV:
  - `adim.csv`: `tarih,saat,adim`. Saatlik satırlar; veri olmayan saatler yazılmaz.
  - `nabiz.csv`: `tarih_saat,nabiz`. Geçerli ölçümler; `HealthLog.VALID_BPM` aralığı dışındakiler yazılmaz.
  - `uyku.csv`: `gece,baslangic,bitis,tur,dakika`. `tur` değeri `derin` ya da `hafif`.
- **Excel uyumu:**
  - Ayırıcı virgül.
  - Dosya başında UTF-8 BOM var, Türkçe Excel'de karakterler bozulmasın.
  - Tarih biçimi ISO (`2026-11-14`, `2026-11-14 13:20`).
- **Hesaplama:** Arka plan iş parçacığında. İş bitince toast: "Dışa aktarıldı: 3 dosya".
- Veriler mevcut `HealthStore.view()` / `HealthView` üzerinden okunur: `hourlySteps`, `heartPoints`, `sleepSegmentsFor`. Ham kayıt biçimi değiştirilmez.

**Test**
- `protocol`: CSV satır üretimi (kaçış kuralı, BOM, sıralama, boş gün) saf fonksiyon olarak test edilir.
- Telefonda:
  - Dosyayı Drive ya da İndirilenler'e kaydedip Excel ya da Google Sheets'te açmak.

## 4. Teslim

- **Sürüm numarası:** versionName 0.10.0. versionCode bir artırılır.
- **Dal ve PR:** Ayrı dal ve PR. Derleme yeşil olmalı. PR açıklamasında şunlar olur:
  - Ne yapıldı.
  - Aşama A sonucu, ya da "proje sahibi denemeli" notu.
  - Riskler.
  - Telefonda denenecekler listesi.
- **Belgeler:** `docs/PLAN.md` sonuna kısa bir "v0.10" bölümü eklenir.
