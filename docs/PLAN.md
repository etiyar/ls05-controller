# LS05 Controller: Durum, Plan ve Risk İncelemesi

Tarih: 12 Kasım 2026. Bu belge projenin tek gerçek kaynağıdır. Önceki devir notları (v1-v6) ve sohbetlerdeki özetler bununla çelişirse bu belge geçerlidir.

Durum özeti: Kendi Android uygulamamız Haylou uygulaması olmadan saate bağlanıyor, doğrulamadan geçiyor, pil ve firmware okuyor, Türkçe bildirim gönderiyor ve saati titretiyor. Projenin "yapılabilir mi" sorusu kapandı. Sıradaki iş, bunu günlük kullanılabilir ve kayıp saati bulabilen bir uygulamaya çevirmek.

---

## 1. Neredeyiz

### 1.1 Gerçek cihazda kanıtlananlar

| Bulgu | Kanıt |
| --- | --- |
| Komut kanalı `60FF/6001` (Write Without Response) → `6002` (notify) | build-7 testi + Haylou Fun bağlanma kaydı |
| Doğrulama zorunlu: `20 02` + 4 bayt kullanıcı kimliği → `20 04 01` | build-7 testi; doğrulamasız iki testte saat yanıtsız kaldı ve ~30 sn sonra bağlantıyı kesti (`status=19`) |
| Firmware `02` → `RH192SV002066` | build-7 testi |
| Pil `03` → `03 <yüzde>`; standart `2A19` ile aynı | build-7 testi (%66) |
| Bildirim `0F`: 4+16 / 2+18 bayt segment, `0F FD`, UTF-16BE, `başlık: gövde` | 3 test bildirimi saatte göründü |
| Türkçe harfler saatte doğru çiziliyor | Kullanıcı gözlemi |
| Titreşim `06 00 00 00 02 02 00 00` → `06 00` | Bildirimle ve tek başına çalıştı |
| Mevcut eşleştirme (BONDED) ikinci uygulamadan kullanılabiliyor | Tüm testler |
| İşlemci Realtek ailesinden | Saatin GATT'ındaki `d0ff-3c17…` ve `6287-3c17…` servisleri Haylou Fun içindeki Realtek OTA kütüphanesinde birebir tanımlı |

### 1.2 Güçlü ama dolaylı olanlar

| Bulgu | Neden dolaylı |
| --- | --- |
| Handle eşlemesi (`0x22`=6001, `0x24`=6002, `0x10`=33F1, `0x1C`=34F1) | GATT sırasından hesaplandı; 8 handle'ın hepsi kayıtla tutarlı ama doğrudan okunmadı |
| `04` saat/tarih biçimi (`04 07EA 0B 0B 13 33 2B 03`) | Kayıtta görüldü, bizim uygulamadan gönderilmedi |
| `18 03 <tarih-saat> …`: zaman damgalı kayıt, muhtemelen nabız | Yalnızca 2 örnek (00:50, 01:10); değer alanı çözülmedi |
| `A2 42`: pil bildirimi | Tek örnek, değer pil ile aynı |
| Uygulama tipi baytları (WhatsApp 7, Telegram 24 …) | Hello Haylou tablosundan; saatte simgeleri denenmedi |

### 1.3 Bilinmeyenler

- `55FF/33F1` doğrulamadan sonra komut kabul eder mi (önemsiz, merak konusu)
- Saat telefona bağlı değilken yayın yapıyor mu, adresi sabit mi (bağlantı koptuktan sonra radarın çalışması için kritik)
- Uyku ve SpO2 biçimleri (adım ve nabız 12 Kasım'da çözüldü, bkz. 4.1)
- Kadran dosyasının iç biçimi (yükleme akışı 12 Kasım'da kaydedildi, bkz. v0.4)
- Firmware: tam çip modeli, güvenli önyükleme, imza, çift bank, UART noktaları

---

## 2. Bugüne kadarki hatalarımız ve dersleri

Bu bölüm suçlama için değil, aynı hatayı tekrarlamamak için.

| Hata | Ne oldu | Ders |
| --- | --- | --- |
| Yanlış protokol | v1, Haylou Fun'daki protobuf (`7E 6D`) yolunu LS05'e uyguladı. Haylou Fun çok modelli; LS05 başka alt sistemi (Youcy/UTE) kullanıyor. | Çok modelli bir uygulamada önce cihazın hangi koda yönlendirildiğini kanıtla. |
| Yanlış komut kanalı | v5, Hello Haylou koduna dayanarak `55FF/33F1` dedi. Gerçek testte yanıt gelmedi. v6 da "çürütüldü" diyerek fazla ileri gitti: `33F1` yalnızca doğrulamasız denendi. | Bağımsız bir uygulamanın kodu, bizim cihazımızın davranışı değildir. "Çalışmadı" ile "çalışmaz" aynı şey değil. |
| Eksik örnek | v3'teki bildirim örneğinde üçüncü segment yoktu; diğer ajan yakaladı. | Örnekleri kayda karşı otomatik testle üret. Bu artık yapılıyor. |
| Birincil kanıt geç kullanıldı | Bağlanma anını içeren geçerli kayıt başından beri vardı ama en sona kaldı; arada iki yanlış kanal denendi. | Önce birincil kayıt, sonra kod çıkarımı. |
| İmza anahtarı | "Yeni sürüm eskisinin üzerine kurulur" dendi. Her GitHub derlemesi farklı test anahtarıyla imzalanıyor (build-6 ve build-7 sertifikaları farklı); Android bunu reddeder. | Kurulum davranışını varsayma, doğrula. Sabit anahtar şart. |
| Arayüz hatası | Android 15'in kenardan kenara kuralı yüzünden üst düğmeler görünmedi. | Hedef SDK'nın davranış değişikliklerini ilk sürümde kontrol et. |
| Kişisel veri sızıntısına yakın olay | Kullanıcı kimliği bir kod yorumunda örnek olarak geçti; commit'ten önce taramayla yakalandı. | Her commit öncesi kimlik/MAC taraması. Bunu CI'ya ekle. |
| Dağınık belgeler | v1 çevrim içi devir belgesi (yanlış protobuf bilgisi), v2-v6 md'ler, README ve kod yorumları birbirinden koptu; README ve bir yorum `55FF`'i hâlâ doğru kanal gibi anlatıyordu (bu sürümde düzeltildi). | Tek gerçek kaynak: bu belge. Eski belgeler "ESKİ" diye işaretlenir. |

---

## 3. Değişmez kurallar

İlke: önce işlev, sonra estetik. Arayüz güzelleştirmesi ancak işlevler kabul testlerini geçtikten sonra, uygun olursa yapılır. Hedef, Haylou Fun'a hiç ihtiyaç bırakmayan verimli bir uygulama.

1. Eşleştirme hiçbir koşulda silinmez veya sıfırlanmaz (`07 00`, `07` kodda yok, eklenmez).
2. Firmware veya kadran aktarımına (`56FF/34F1`, Realtek OTA servisleri) kurtarma yolu kanıtlanmadan yazılmaz.
3. Doğrulanmamış komut otomatik gönderilmez. Yeni komut önce kayıtla veya kontrollü tek testle doğrulanır.
4. Kullanıcı kimliği, MAC adresi ve bildirim içerikleri depoya ve loglara girmez.
5. Sağlık değerleri, alanları kontrollü ölçümle doğrulanmadan kullanıcıya ölçüm olarak gösterilmez.
6. Saat doğrulamada `4` (onay bekliyor) dönerse otomatik onay veya tekrar deneme yapılmaz.
7. Yanlış veya eksik kimlikle otomatik doğrulama denemesi sınırlıdır (öneri: oturum başına 1, ardından kullanıcıya sor).

---

## 4. Plan

Boyutlar göreli: K (küçük), O (orta), B (büyük). Süre tahmini bilerek verilmedi.

### Aşama 0: Altyapı temizliği (K, v0.2'den önce) — imza ve gizli veri taraması TAMAMLANDI (12 Kasım)

| İş | Neden | Kabul |
| --- | --- | --- |
| Sabit imza anahtarı. Şimdilik kullanıcı adımı gerektirmeyen yol: test anahtarını GitHub Actions önbelleğinde tut. Depo özel olunca keystore GitHub Secret'a taşınır; depoya asla girmez. | Güncellemeler üzerine kurulabilsin, kimlik ve ayarlar silinmesin | İki ardışık derlemenin sertifika parmak izi aynı |
| CI'ya gizli veri taraması (kimlik deseni, MAC, `.log`) | Depo herkese açık | Desen bulununca derleme kırmızı |
| Eski belgeleri "ESKİ, bkz. docs/PLAN.md" diye işaretle (v1 çevrim içi devir belgesi dahil) | Yanlış bilginin tekrar okunması | Her eski belgenin başında uyarı |
| Kuyrukta takılı ilk GitHub işini iptal et | Gürültü | Actions listesi temiz |

### v0.2: Günlük kullanılabilir sürüm (B) — M1, M2, saat eşitleme (v0.2.0) ve M3 alarm/rahatsız etme (v0.3.0) yazıldı; gerçek cihaz testi bekliyor

Tek sürüm, üç sıralı iç hedef. Her hedef kendi kabul testini geçmeden sonrakine geçilmez.

**M1: Arka plan bağlantısı**
- v0.2.0'da yalnızca `connectedDevice` ön plan servisi + autoConnect var. CompanionDeviceManager henüz yok; ön plan servisi Xiaomi'de yetersiz kalırsa eklenecek.
- Otomatik yeniden bağlanma: üstel bekleme, her bağlantıda doğrulama, kural 7'deki deneme sınırı.
- Haylou Fun açıkken çakışmayı algıla ve kullanıcıya söyle; döngüye girme.
- Telefon açılışında (boot) ve uygulama güncellemesinde yeniden başlama.
- Xiaomi/HyperOS rehberi: otomatik başlatma, pil kısıtlaması yok.

**M2: Bildirim iletimi**
- NotificationListenerService; uygulama bazlı açma/kapama listesi.
- Uygulama tipi baytı (WhatsApp, Telegram …) ve saatte simgesinin denenmesi.
- Emoji ve 4 baytlık karakterleri temizle; kesmeyi kod noktası sınırında yap (şu an 254 baytlık kesme bir emojiyi ortadan bölebilir).
- Tekrarları ve gürültüyü ele: grup özetleri, güncellenen bildirimler, kalıcı bildirimler (müzik, navigasyon).
- Bağlantı yokken kısa kuyruk (öneri: son 10 dk, en fazla 20 bildirim), bağlanınca gönder.

**M3: Basit ayarlar (Haylou Fun eşdeğerliği, 1. kısım)**
- Alarm (`06`), rahatsız etme (`15`), bağlanınca saat/tarih eşitleme (`04`).
- `04` biçimi Haylou Fun kodundan kesinleşti: `04 YY YY AA GG ss dd sn TZ`, son bayt UTC farkı (saat). Haftanın günü değil; kayıttaki `03` = UTC+3.
- Yöntem: Haylou Fun'ın baytları 12 Kasım kayıt seansında yakalandı ve Haylou Fun koduyla birebir eşleşti (bkz. bölüm 4.1). v0.3.0'da yazıldı.
- Alarm: saatteki alarmlar telefondan okunamıyor. Doğruluk kaynağı uygulamanın kaydı; ayar ancak saat `06 <yuva>` onayı verince kaydedilir.
- Rahatsız etme: ayar ancak saat `15 …` yankısı verince kaydedilir. Saatteki elle açma/kapama `0c 02` durum baytının 0x08 bitinden okunabilir (orta güven).
- İlk kullanım = kontrollü test: her biri bir kez gönderilir, saatin ekranında doğrulanır, sonuç buraya yazılır.
- 12 Kasım: alarm yuvaları 1 ve 2, rahatsız etme açık/kapalı saat tarafından onaylandı (`06 01`, `06 02`, `15` yankısı). Alarm 2 (21:20) saatte çaldı: alarm uçtan uca GEÇTİ.
- 12 Kasım 21:07: bağlantı iki kez koptu (status 8, 62), servis kendisi yeniden bağlandı, arada gelen 3 bildirim kuyruktan iletildi. M1/M2 yeniden bağlanma testi GEÇTİ.
- Uzun bildirimde saat `0F FD 04 A0` döndü: 254 bayt gönderildi, 160 bayt alındı. Gerçek saat cetvel testi: saat yaklaşık 78 karakter gösterip "..." koyuyor. v0.5.0'dan beri metin 80 UTF-16 biriminde kesiliyor (vekil çift bölünmez).

**v0.2 kabul testleri**

| Test | Ölçüt (tartışılabilir başlangıç değerleri) |
| --- | --- |
| 24 saat, ekran kapalı, varsayılan Xiaomi ayarı ve "kısıtlama yok" ayrı ayrı | Bağlı kalma ≥ %95; her kopma loglanır |
| 10 dk menzil dışı, geri dönüş | Yeniden bağlanma + doğrulama ≤ 30 sn |
| 100 gerçek bildirim, ekran kapalı ve Doze dahil | `0F FD` onayı ≥ %99, p95 gecikme ≤ 3 sn |
| Bluetooth kapat/aç, telefon yeniden başlatma, uygulamayı son uygulamalardan silme, saat yeniden başlatma | Müdahalesiz toparlanma |
| Uzun metin, Türkçe, emoji, boş başlık | Doğru kesme, bozuk karakter yok |
| Yanlış kimlik; Haylou Fun aynı anda açık | Sınırlı deneme, açık hata mesajı, döngü yok |
| 3 gün pil | Telefon ve saat pili Haylou Fun dönemiyle karşılaştırılır |
| Loglar | İçerik, kimlik, tam MAC yok |

### v0.3a: Sağlık verisi ve hava durumu (O-B), Haylou Fun eşdeğerliği 2. kısım

- Çözüldü (bölüm 4.1): adım geçmişi `0a`, canlı adım `09`, nabız geçmişi `18 FA`, canlı nabız `18 03`, günlük nabız özeti `18 04`. v0.3.0'da "Sağlık (deneysel)" bölümü bunları okuyup bellekte gösteriyor.
- Kabul testi: uygulamadaki "Bugün adım" saatin ekranındaki adımla karşılaştırılır. 12 Kasım 21:29: uygulama 1447, saat 1447. GEÇTİ (1/3).
- Canlı nabız `18 03` çoğu zaman 0 geliyor, aynı dakikanın geçmiş kaydında ise değer var. 0 "ölçüm yok" sayılmalı; son nabız geçmiş bloklarından alınmalı (sonraki sürüm).
- Uyku (`1d 01` → kayıtta yalnızca `1d 02`, veri yok) ve SpO2 çözülmedi. Uyku için saat bir gece takılıyken yeni bir kayıt gerekir.
- Saklama yerelde; CSV ve Health Connect dışa aktarma.
- Hava durumu (`11`, kullanıcı ara sıra kullanıyor): isteğe bağlı, internetten ücretsiz bir kaynakla (ör. Open-Meteo, anahtar gerektirmez). İnternet yalnızca bu özellik açıksa kullanılır.
- Haylou Fun, v0.3a kabul testlerini geçince bırakılır.
- Karar: kayıtları sürekli mi dinleyelim, bağlanınca toplu mu çekelim (pil karşılaştırması ile).

### v0.3b: Kayıp saat (O)

- Son görülen konum: kopma anını değil, RSSI'nin son kez güçlü olduğu anın konumunu kaydet (eşik test edilecek, öneri ≥ −70 dBm).
- Kalite eşiği: doğruluğu kötü (öneri > 50 m) veya bayat (> 2 dk) konum iyi konumun üzerine yazılmaz; doğruluk yarıçapı saklanır, harita üzerinde daire olarak gösterilir.
- Kopma uyarısı: birkaç başarısız yeniden bağlanma ve kısa bekleme sonrası; ev gibi güvenli bölgelerde susturma.
- RSSI yakınlık radarı ve "saati titret".
- Önkoşul testi: saat bağlı değilken yayın yapıyor mu, adresi sabit mi.
- İzinler: arka planda konum; Android 14+ ön plan servis türleri (`connectedDevice` + `location`). Xiaomi'de gerçek test.

Kayıp saat yalnızca telefon üzerinden çözülür (kullanıcı kararı, 12 Kasım): ek cihaz veya sunucu yok, en kolay erişilebilir yol.

> Arayüz sürümü önerisi (tartışmada): `docs/V04_ARAYUZ_PLANI.md`. Kabul edilirse arayüz v0.4 olur, kadran yükleme v0.5'e kayar.

### v0.4: Saat kadranı (O-B)

- Kullanıcı kadran değiştiriyor. Hazır kadranlar arasında geçiş komutu yok; değiştirmek, çevrim içi galeriden dosya indirip yüklemek demek.
- 12 Kasım kaydındaki gerçek akış (önceki `26 02` tahmini yanlıştı; doğru sınıf `UpdateWatchUIEvent`): `1a 01` yapılandırma sorgusu, `1a 02` başlat (yankı `1a 02`), veri `61FF/6101` (handle 0x28) üzerinde 4147 baytlık parçalar (17 yazma), her parça `26 03 04` ile onaylanır, yaklaşık 150 parça ≈ 620 KB, ≈ 2 dk; bitiş `1a 03 00` (yankı `1a 03 00`), ardından `a2 3f`. Başta 0x28 okunur (`00 f4`).
- Bu, üreticinin resmi yükleme yolu, firmware değil. Yine de bozuk dosyanın etkisi bilinmiyor.
- Sıra: (1) Haylou'nun kadran galerisi hâlâ çalışıyor mu kontrol et, (2) Haylou Fun'ın bir kadran yüklemesini kaydet, (3) aynı resmi dosyayı bizim uygulamadan birebir yükle, (4) ancak sonra özel kadran araştırılır.

### v0.5.0: İşlevsel tamamlama (yazıldı, gerçek cihaz testi bekliyor)

- Bildirim 80 karakter, gelen ve cevapsız arama, saatten kumanda, ekran ve profil (`05`), kaydırma sonrası bildirim kaybına karşı servis başlatma. Ayrıntı: 4.2 ve README.

### Sonra: Otomasyon (K-O)

- Tasker/intent arayüzü; kural motoru (sessiz saatler, kişiye göre titreşim, saat düğmesi olayı `12`).

### Kayıt seansı (kullanıcı, bir kez, yaklaşık 10 dk)

TAMAMLANDI (12 Kasım, 07:16–07:44, 2977 ATT kaydı). Kullanıcının saat notları her adımla eşleşti. Eksik kalan: uyku verisi (gece kaydı gerekir) ve SpO2.

### 4.1 12 Kasım kaydından çözülen komutlar

Güven: Y = kayıt ve Haylou Fun kodu birebir uyuşuyor; O = tek kaynak veya az örnek.

Hello Haylou kuralı: o uygulama birçok saat markasını destekliyor. Yalnızca LS05 ile aynı UUID'leri kullanan UTE sürücüsü (`c8/a3`) okunur ve oradan gelen her bilgi, kayıt veya Haylou Fun ile doğrulanana kadar "aday" sayılır. Tek başına Hello Haylou'ya dayanan komut gönderilmez. Yasak listesi oradan da teyitli: `07 00` = eşleşmeyi silme.

| Komut | Biçim | Güven |
| --- | --- | --- |
| Alarm `06` | `06 [gün maskesi] [sa] [dk] 02 08 06 00 [yuva 1..3]`; kapalıysa `06 60 [sa] [dk] 00 00 00 00 [yuva]`. Maske bit0 pazar … bit6 cumartesi (62 hafta içi, 127 her gün, 65 hafta sonu). Yanıt `06 [yuva]`. Önce `87 00` (alarm sayısı sorgusu) gönderiliyor. | Y |
| Rahatsız etme `15` | `15 00 [baş sa] [baş dk] [bit sa] [bit dk] [01/00]`, yankı aynısı | Y |
| Hava `11` | 3 paket: `11 01` bugün (kod 2B LE, şimdi/yüksek/düşük, PM2.5 2B, hava kalitesi 2B, şehir UTF-16BE en çok 8 bayt), `11 02` 4 gün, `11 03` 2 gün. Eksi sıcaklıkta bit7. Yanıt `11 01/02/03`. | Y düzen, O kod anlamları |
| Hava kodları | 1 güneşli, 2 parçalı bulutlu, 3 kapalı, 4 sağanak, 5 gök gürültülü, 6 karla karışık, 7 çisenti, 8 şiddetli yağmur, 9 kar, 10 toz, 11 pus, 12 fırtına (başka saat sürücüsünün eşlemesinden) | O |
| Adım geçmişi `0a` | İstek `0a [YY YY AA GG] 00 00` (o günden beri). Kayıt 18 bayt: tarih, saat, toplam 2B, koşu (baş dk, son dk, dk, adım 2B), yürüme (aynı). Sonu `0a fd [XOR]`. Okuma kayıtları silmiyor (aynı kayıt iki eşitlemede geldi). | Y toplam, O dakika alanları |
| Canlı adım `09` | Aynı 18 baytlık düzen; saat yürürken kendiliğinden saniyede bir yollar, istek yok. | Y |
| Nabız geçmişi `18 FA` | İstek `18 FA [tarih]`. Önce `18 04` özeti (en yüksek, en düşük, ortalama), sonra `18 [tarih] [bitiş saati] + 12 değer` (10 dk aralık, FF yok; blok bitiş saatinden 2 saat öncesini kapsar), sonu `18 fd [XOR]`. | Y |
| Canlı nabız `18 03` | `18 03 [tarih] [sa] [dk] [nabız]`, 10 dakikada bir kendiliğinden | Y |
| Geçmiş sonu XOR | Kayıtların komut baytı hariç XOR'u; 4/4 eşitlemede tuttu | Y |
| Uyku `1d 01 [tarih]` | Hello Haylou: başlangıç `1d 01 …`, kayıtlar `1e …` ayrı kanaldan (`61FF/6102`, uygulama zaten abone), son `1d 02`. Kayıtta yalnızca `1d 02` geldi (o gece veri yoktu). `1e` iç düzeni çözülmedi; uygulama bilinmeyen çerçeveyi ham logladığı için bir gece sonraki log yeterli. | O |
| SpO2 `86 FA` | İstek `86 FA [tarih] 00 00` (Haylou Fun), veri `86 FA …`, son `86 FA FD` (Hello Haylou). İki uygulama uyuşuyor. | O |
| Hareketsizlik uyarısı `14` | `14 [açık] [aralık dk] 02 05 00 00 [baş sa] [baş dk] [bit sa] [bit dk] [öğle arası]` (Haylou Fun); Hello Haylou da `14` diyor | O |
| Durum `0c 02` | Yanıt `0c 02 [bayrak]`; rahatsız etme açılınca 0x42 → 0x4a (bit 0x08) | O |
| Müzik `12` | `12 0c [1 çalıyor / 2 durdu]`, `12 0d [ses %]`; saat düğmelerden `12 xx` yollayabilir. v0.5'te çözüldü, bkz. 4.2. | Y |
| Dil `25 ab [kod]` | Kayıtta 02 (İngilizce). Haylou Fun tablosunda `tr_TR` = 16. Saat yazılımı Türkçe menüyü destekliyor mu bilinmiyor; tek kontrollü test adayı. | O |
| Birim/saat biçimi `01` | `01 [mesafe birimi] [saat biçimi]` | Y |
| Profil `05` | Boy, kilo, yaş, cinsiyet, adım hedefi (8000 = `00 00 1f 40`) vb. Kişisel veri: logda gizlenir, depoya girmez. Tam düzen 4.2'de. | Y |

### 4.2 v0.5.0 bulguları (13 Kasım, kod okuması + 11 ve 12 Kasım kayıtları)

Kaynaklar: Haylou Fun (`youcy/event`, `com.yingsheng.protocol.youcy.task`, `rd/b`), Hello Haylou (`c8/a3` ve `MiBandSupport` geri çağrısı, classes10 tek sınıf yeniden açıldı), kayıtlar (`nt.log` 11 Kasım: iki gelen arama; `s.log` 12 Kasım). Y = iki kaynak ve kayıt tutarlı; O = tek kaynak ya da kaynaklar arasında fark var.

| Komut | Biçim | Güven |
| --- | --- | --- |
| Gelen arama metni | `0F 00 00 [uzunluk]` + ad (UTF-16BE, en çok 30 bayt = 15 birim), devam `0F 01 …`, `0F FD`. Tip 0, `0F` bildirimiyle aynı paketleme. Haylou Fun `UpdateMobCallStatusEvent`/`Task`, Hello Haylou `ALERT_CALL_TEXT`, kayıtta iki arama (`0F 00 00 18`, `0F 00 00 0A`). | Y |
| Arama bitti | `0D 04`; saat aynen yankılıyor. Haylou Fun (arama bitince), Hello Haylou `ALERT_CALL_TEXT_STOP`, kayıt. | Y |
| Arama titreşimi | Düzen `06 00 00 00 [sayı] [süre] 00 00` (Haylou Fun `SetVibrateEvent`). Değer kaynaklarda farklı: kayıtta `FF FF`, Haylou Fun 4.0.7 kodunda `03 20`, Hello Haylou `01 0A 02 01`. Uygulama kayıttakini (`FF FF`) gönderiyor, bitişte mutlaka durduruyor, en geç 60 sn sonra susturuyor. | O |
| Titreşimi durdur | `06 00 00 00 00 00 00 00`; Haylou Fun, Hello Haylou `VIBRATE_END`, kayıt aynı. Yanıt `06 00`. | Y |
| Cevapsız arama | Ayrı komut yok; tip 3 bildirim (Haylou Fun `SendMsgEvent` arama öğesi 3 başlıksız gönderir, Hello Haylou tablosunda 3 arama). Saatteki simgesi denenmedi. | O |
| Saatten kumanda | `12 [kod]`: `07` oynat/duraklat, `08` sonraki, `09` önceki, `0D` ses aç, `0E` ses kıs, `0A` telefonu bul (ek bayt yoksa aç/kapa, `01` başlat, `00` durdur), `02` aramayı reddet, `11`/`0F` kamera aç/kapat. `D1 0A` de telefonu bul. Haylou Fun `OnClickKeyTask` + `rd/b`, Hello Haylou `a3` (tam 2 baytlık paketler) tutarlı. Kayıtta saatten gelen tuş yok; ilk kullanım kontrollü test. Uygulama müzik ve ses kodlarını yalnızca tam 2 baytlık pakette uygular (`12 0D [ses]` yankısı döngü yapmasın); `02`, kamera ve bilinmeyenler yalnızca loglanır. | Y kod, test bekliyor |
| Müzik durumu | `12 0C [1 çalıyor / 2 durdu]` (yanıt `12 0C 00`), `12 0D [ses %]`. Haylou Fun `MusicControlTask`, Hello Haylou `SET_MUSIC_STATE`, kayıt (`12 0c 02`, `12 0d 06`, `12 0d 64`). Uygulama bağlanınca ve kumandadan sonra gönderiyor. | Y |
| Profil ve ekran `05` | `05 [boy 2B] [kilo 2B] [ekran sn] [hedef 4B] [bilek 0/1] [nabız üst, kapalı FF] 00 [yaş] [cinsiyet 1 erkek / 2 kadın] [kayıp uyarısı] [dil] [mesafe 1 km / 2 mil] [nabız alt, kapalı 00]`. Haylou Fun `SetDeviceParamsEvent` ve Hello Haylou `u0` aynı düzen. Kayıttaki 39 paketin 38'i uygulamanın üreticisiyle bayt bayt aynı (yerel kontrol, değerler yazılmadı); farklı olan tek paket başka bir oturumda nabız uyarıları ve dil baytı değişmiş hali. Yanıt tek bayt `05`. | Y |
| `05` bayt 15 ("d6") | Haylou Fun `watch_device_lost_warning` ayarı: saatin bağlantı kopunca uyarması. Hello Haylou hep 0, kayıtta 0. Uygulama 0 gönderir. | O |
| `05` bayt 16 ("E") | Haylou Fun `SetWatchLanguageEvent.E()`: dil kodu (`25 AB` ile aynı tablo, 2 İngilizce, 16 Türkçe). Hello Haylou hep 2, kayıtta 2 (bir oturumda 1). Uygulama 2 gönderir. | Y anlam, O etki |
| Ekran süresi seçenekleri | 5, 10, 15 sn (Haylou Fun ayar penceresi). | Y |

Gönderilmeyenler: `12 02` ile aramayı reddetme (ek izin gerekir), kamera kumandası, `D1` ile başlayan diğer kodlar (yalnızca Hello Haylou'da), Türkçe dil kodu (ayrı kontrollü test).

### Araştırma kolu (paralel, YALNIZCA OKUMA)

| No | İş | Saate yazma |
| --- | --- | --- |
| R1 | Realtek OTA servisinin okunabilir karakteristiklerini oku (`ffd2`-`ffd5`, `ffe0`, `fff1`): çip/görüntü bilgisi, belki bank düzeni. Anlamları Realtek SDK'sından çıkar. | Yok |
| R2 | Haylou Fun ve Hello Haylou APK'larında gömülü firmware veya kadran dosyası ara | Yok |
| R3 | Find Hub (FMDN) ve OpenHaystack gereksinimlerini çıkar; RTL876x'te dönen anahtar hesabının fizibilitesi | Yok |
| R4 | Kadran akışını (`26 01` … `26 03`) Hello Haylou'nun LS05 için kullanıp kullanmadığını koddan doğrula | Yok |

Bu koldan firmware veya kadran yazmaya geçiş, ayrı bir karar ve kurtarma yolunun kanıtı gerektirir. Kullanıcı şu an donanım almak istemiyor; UART olmadan kurtarma yolu yok.

---

## 5. Kararlar

Verilenler (kullanıcı, 12 Kasım 2026):

| Konu | Karar |
| --- | --- |
| Haylou Fun | Bırakılacak. Uygulamamız tek başına yetecek şekilde geliştirilir (v0.2 M3). |
| Öncelik | Önce işlev, sonra estetik (uygun olursa). |
| Depo | Şimdilik herkese açık; özel yapma ertelendi. Kişisel veri kuralı bu yüzden aynen geçerli. |
| Kişisel gözlem ağı | Reddedildi. Kayıp saat yalnızca telefon üzerinden. |
| Kullanılan işlevler | Alarm, rahatsız etme, hava durumu (ara sıra), adım, nabız ve diğer sağlık verileri, kadran değiştirme. Bildirimler test edildi. |

Açık kalanlar:

| Konu | Seçenekler | Kimde |
| --- | --- | --- |
| Kimlik saklama | SharedPreferences (şimdiki) / Android Keystore ile şifreli | Ajanlar |
| Sağlık kayıtları | Sürekli dinleme / toplu çekme | v0.4'te ölçümle |

## 6. İkinci gözden geçirme: bu planda nerede yanılıyor olabiliriz

### 6.1 Planın kendi varsayımları

| Varsayım | Yanlışsa ne olur | Nasıl erken anlarız |
| --- | --- | --- |
| Haylou Fun her bağlantıda aynı `20 02` kimliğini gönderiyor, saat her seferinde `1` dönüyor | Otomatik yeniden bağlanma bir gün `4` (onay) alıp takılır | M1 dayanıklılık testinde her doğrulama sonucu loglanır |
| Saat bağlı değilken yayın yapıyor | v0.3 radarı bağlantı koptuktan sonra çalışmaz | v0.3 önkoşul testi, ilk iş |
| CompanionDeviceManager HyperOS'ta ön plan servisinden daha dayanıklı | Gereksiz karmaşıklık | M1'de ikisi yan yana ölçülür |
| `18 03` kaydı nabız | Yanlış sağlık verisi | Kural 5; Haylou Fun ile karşılaştırma |
| Uygulama tipi baytları bu saatte de aynı | Yanlış simge | M2'de her tip için tek test |
| Bildirim dakikada çok sayıda gelirse saat yetişir | Kaybolan veya karışan bildirim | M2'de art arda 20 bildirim testi |
| 50 ms paket arası yeterli | Uzun mesajlarda bozulma | M2 uzun metin testi |

### 6.2 Android ve Xiaomi tarafı

- HyperOS arka plan uygulamalarını öldürebilir; ayarlar güncellemeyle sıfırlanabilir. Kullanıcıya ayar kontrolünü gösteren bir ekran gerekir.
- Android 14+ ön plan servis türü ve izin kuralları; `location` türü yanlış zamanda başlatılırsa servis çöker.
- Bildirim dinleme izni kullanıcı tarafından kapatılabilir; uygulama bunu fark edip söylemeli.
- `onCharacteristicChanged` ve `onCharacteristicRead` API 33 öncesi/sonrası farkı; ikisi de ele alındı, ama eski Android sürümünde test edilmedi.

### 6.3 Protokol tarafı

- Bilinmeyen çerçeveler (`1C`, `A2 …`) yorumlanmadan atılıyor; yeni bir zorunlu el sıkışma adımı olabilir. Loglanmaya devam edilmeli.
- `33F1` yapılandırma okuması 20 bayt veriyor; özellik bayrakları (ör. kadran, SpO2 desteği) burada olabilir. Henüz çözülmedi; yetenek haritası için değerli.
- Saat aynı anda tek bağlantı kabul ediyor; telefonda iki Bluetooth uygulaması yarışırsa davranış tanımsız.

### 6.4 Süreç tarafı

- İki ajan arasında belge kayması: bu belge tek kaynak; her değişiklik burada yapılır, notlar bundan türetilir.
- Hipotezi kanıt gibi yazmak: her iddiaya güven düzeyi ve kanıt kaynağı eklenir (bu belgedeki tablolar gibi).
- Kullanıcının zamanı ve kotası: her gerçek cihaz testi tek değişkenli, kısa ve logu kopyalanabilir olmalı.
- Depo herkese açık: her commit öncesi kişisel veri taraması (aşama 0'da CI'ya eklenecek).

### 6.5 Cihaz güvenliği

- Saat güncelleme almıyor; bozulursa yedeği yok. Kural 1 ve 2 bu yüzden pazarlıksız.
- Yanlış kimlikle doğrulama saatte yeni sahip onayı açabilir; onaylanırsa Haylou Fun bağı kopabilir veya veriler silinebilir. Kural 6 ve 7.

## 13 Kasım akşam: v0.5.0 cihaz testleri

- Gelen arama: ad görünüyor, saat titriyor; telefondan cevap ve red sonrası `0D 04` ile susuyor. GEÇTİ.
- Cevapsız arama: tip 3 bildirim saat tarafından alındı (`0F FD 03 28`) ama saatte görünür bir kayıt bırakmadı. v0.5.1: mesaj tipiyle (4) gönderilir.
- Saatteki kırmızı düğme `12 02` gönderiyor (doğrulandı). v0.5.1: isteğe bağlı "Saatten aramayı reddet" (ANSWER_PHONE_CALLS, TelecomManager.endCall).
- Müzik kumandası `12 07` oynat/duraklat, `12 08` sonraki, `12 09` önceki: GEÇTİ (kodlar saatle doğrulandı). Ses ve telefonu bul denenmedi.
- Profil `05`: ilk kontrollü gönderim onaylandı, ekran süresi 10 sn saatte doğrulandı. GEÇTİ.
- Kaydırıp kapatma sonrası WhatsApp bildirimi: GEÇTİ (1/1).
- 80 karakter: saatte yaklaşık 78 karakter + "..." GEÇTİ. Not: noktasız "ı" saat yazı tipinde boşluklu görünüyor (saat yazılımı); isteğe bağlı harf sadeleştirme ileride.
- 13 Kasım 21:31: saatteki kırmızı düğme (12 02) arama bildirimindeki "Decline" eylemiyle aramayı reddetti, ardından 0D 04 ile saat sustu. GEÇTİ (v0.5.2). İzin gerekmedi.

### v0.6.1 (13 Kasım)
- Alarm hatırlatma notu: en çok 40 karakter, yalnızca telefonda. Alarm anında saate sıradan bildirim (tip 4) olarak gider. Saatin alarmı bağımsız.
  - Zamanlama: Android 13+ USE_EXACT_ALARM, 12-12L SCHEDULE_EXACT_ALARM. İzin yoksa setAndAllowWhileIdle ve "gecikebilir veya gönderilemeyebilir" uyarısı.
  - Planlanan andan 5 dakika sonra gönderilmez; kuyrukta da 5 dakika bekler. Aynı yuva aynı gün bir kez. Tetiklenince ayar hâlâ aynı anı üretmiyorsa atılır.
  - Yeniden zamanlama: açılış, güncelleme, saat ya da saat dilimi değişimi, izin değişimi, alarm ya da not değişimi, uygulama açılışı.
  - Testler: V061Test (gün maskesi, hafta sonu atlama, yaz saati boşluğu, geçerlilik, 5 dakika). Cihaz testi bekliyor.
- Uygulama listesi: iletimi açık ve son 3 uygulama üstte, gerisi "Tüm uygulamalar (N)" altında. Arama hepsini kapsar.
- Saatlik adım sütunları daha görünür; tema seçicide seçili seçenek nane; eski "elle dene" açıklaması kalktı.

### Kadran 1. kapı (14 Kasım): dosya biçimi çözüldü

Kaynak: 12 Kasım kaydındaki yükleme (2426 yazma) birebir yeniden kuruldu: 584.296 bayt. Ağ gerekmedi; Haylou sunucularına bu ortamdan erişilemiyor. Dosya üreticiye ait, repoya konmaz.

Aktarım (Haylou Fun `SendPlateTask`, kayıtla birebir):
- `1a 01` yapılandırma (yanıt `1a 01 ff ff ff ff 00 f0 00 f0 00 00`: 240x240).
- `1a 02` başlat, saat `1a 02` ile hazır der.
- Dosya 4096 baytlık bloklara, bloklar MTU-3 (241) baytlık parçalara bölünür. Her parça `61FF/6101` üzerine yanıtsız yazma: `1b [sıra hi] [sıra lo] + veri`. Sıra 0'dan dosya sonuna kadar artar. Her 10 yazmada 30 ms bekleme.
- Her blok sonunda saat `26 03 04` (devam) der. `26 03 03 [hi] [lo]`: veri kopuk, o sıradan yeniden gönder. `26 03 02` ve diğerleri: hata, aktarım durur.
- Bitiş: uygulama `1a 03 00`, saat `1a 03 00` ile başarı der. Haylou Fun 10 sn yanıtsızlıkta hata sayar.

Dosya biçimi (doğrulandı):
- 24 bayt başlık: `[kimlik u16 LE][00 00][veri boyu u32 LE = dosya-24][CRC32 u32 LE, 24. bayttan sona][en 240][boy 240][00 01][ff x6]`.
- CRC32 standart zlib CRC'si, kayıttaki dosyada eşleşti. Saatin hata kodları (`26 03 02`, Haylou kodunda "INVALID CRC ERROR") saatin dosyayı doğruladığını gösteriyor. Kanıt değil, güçlü işaret.
- 0x18'den itibaren 24 baytlık öğe tablosu, 0 türüyle biter: `[tür u16][en u16][konum u32][boy u16][x u16][y u16][00 00][kare sayısı u8][7 bayt, kopyalanır]`.
- Görüntüler sıkıştırmasız RGB565, büyük uçlu (big-endian). Konum dosya başına göre.
- Kayıttaki kadranın öğeleri: 1 akrep, 2 yelkovan, 3 saniye (240x240, saat döndürür), 10 önizleme, 17 arka plan, 13 gün rakamları (`-./0123456789:`, 14 kare), 15 gün adları (7 kare), 25 adım rakamları.

Sonuç: özel kadran yapılabilir. En güvenli yol, bu dosyanın yapısını aynen koruyup yalnızca arka plan ve önizleme görüntüsünü değiştirmek ve CRC'yi yeniden hesaplamak.
Sıradaki karar (kapı 2): kayıttaki resmi dosyayı bizim uygulamadan birebir yüklemek.

### Kadran 2. kapı (14 Kasım 01:45): GEÇTİ
- v0.7.0 kayıttaki resmi dosyayı yükledi: 2426 paket, 44 sn, saat `1a 03 00` ile kabul etti, kadran çalışıyor.
- `1a 01` yanıtı: `1a 01 [kadran kimliği u32 BE] [en] [boy] 00 00 10 00 00`. 12 Kasım'da kimlik `ff ff ff ff` (yerleşik kadran), şimdi `00 00 13 8a` = dosya başlığındaki 5002. Saatteki kadran buradan tanınabilir.
- Aktarım sırasında saat `1a 03 03 06 d9` ile 1753. paketten yeniden gönderim istedi (yanıtsız yazmada kayıp). Yeniden gönderim yolu çalıştı. Ack ön eki `26` değil `1a` da olabiliyor; kod ikisini de kabul ediyor.
- Son blokta onay gelmedi; 3 sn sonra bitiş gönderildi, saat kabul etti (Haylou Fun da onay beklemiyor).
- Gözlem: ekran açılınca saniye ibresi birkaç saniye hızlıca yerine dönüyor. Dosya birebir aynı olduğundan büyük olasılıkla saat yazılımının davranışı; Haylou Fun ile yüklenmiş hâlinde de olup olmadığı soruldu.

### Kadran yazıcı cihaz testleri (14 Kasım 13:19): 1 ve 2 GEÇTİ
- Test 1: FW Logo, FaceWriter ile yeniden yazılmış, kimlik 0xF0F0. Saat kabul etti.
- Test 2: ibresiz dijital (tür 17, 10, 4, 5), FaceWriter çıktısı. Saat gerçek saati doğru gösterdi (13:19): 14 karelik "-,/0123456789:" sırası, hücre yerleşimi ve ibresiz kadran doğrulandı.
- Sıradaki: aşama 1 .tbwf derlemesi (Mus ya da 1526); ortak sprite ve katman sırası ayrı denenecek.

### v0.8.1 cihaz testi (14 Kasım 14:46): GEÇTİ
- Hava durumu (Open-Meteo → `11 01/02/03`) saatte göründü. WMO → saat kodu eşlemesi kullanımda.
- FW kadranlarında Türkçe gün adı ve yeni adım kutusu.
