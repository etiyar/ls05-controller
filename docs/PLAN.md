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
- Saat telefona bağlı değilken yayın yapıyor mu, adresi sabit mi (kayıp saat ve gözlem ağı için kritik)
- Sağlık geçmişi biçimleri (adım, uyku, SpO2)
- Kadran dosya biçimi ve yükleme akışının bu modelde çalışıp çalışmadığı
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

### Aşama 0: Altyapı temizliği (K, v0.2'den önce)

| İş | Neden | Kabul |
| --- | --- | --- |
| Sabit imza anahtarı: keystore GitHub Secret olarak, depoya asla girmez | Güncellemeler üzerine kurulabilsin, kimlik ve ayarlar silinmesin | İki ardışık derlemenin sertifika parmak izi aynı |
| CI'ya gizli veri taraması (kimlik deseni, MAC, `.log`) | Depo herkese açık | Desen bulununca derleme kırmızı |
| Eski belgeleri "ESKİ, bkz. docs/PLAN.md" diye işaretle (v1 çevrim içi devir belgesi dahil) | Yanlış bilginin tekrar okunması | Her eski belgenin başında uyarı |
| Kuyrukta takılı ilk GitHub işini iptal et | Gürültü | Actions listesi temiz |

### v0.2: Günlük kullanılabilir sürüm (B)

Tek sürüm, üç sıralı iç hedef. Her hedef kendi kabul testini geçmeden sonrakine geçilmez.

**M1: Arka plan bağlantısı**
- CompanionDeviceManager ile saati ilişkilendir; bağlıyken `connectedDevice` türünde ön plan servisi; saat tekrar görününce uyan. Saf ön plan servisiyle karşılaştırmalı test.
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

**M3: Saat eşitleme**
- Bağlanınca `04` ile saat/tarih (kayıttaki biçim). Önce tek kontrollü test.

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

### v0.3: Kayıp saat (O)

- Son görülen konum: kopma anını değil, RSSI'nin son kez güçlü olduğu anın konumunu kaydet (eşik test edilecek, öneri ≥ −70 dBm).
- Kalite eşiği: doğruluğu kötü (öneri > 50 m) veya bayat (> 2 dk) konum iyi konumun üzerine yazılmaz; doğruluk yarıçapı saklanır, harita üzerinde daire olarak gösterilir.
- Kopma uyarısı: birkaç başarısız yeniden bağlanma ve kısa bekleme sonrası; ev gibi güvenli bölgelerde susturma.
- RSSI yakınlık radarı ve "saati titret".
- Önkoşul testi: saat bağlı değilken yayın yapıyor mu, adresi sabit mi.
- İzinler: arka planda konum; Android 14+ ön plan servis türleri (`connectedDevice` + `location`). Xiaomi'de gerçek test.

**Tartışma maddesi: kişisel gözlem ağı.** Evde, işte, arabada duran eski Android telefonlar veya ucuz bir ESP32, saatin yayınını görüp küçük bir sunucuya (ör. Cloudflare Workers) "şu saatte burada" bildirir. Firmware gerektirmez. Önkoşul yukarıdaki yayın testi. Donanım alımı gerektirebilir; kullanıcı kararı.

### v0.4: Sağlık verisi (O-B)

- `18 03` kayıtlarını topla; değer alanını Haylou Fun'ın gösterdiği değerlerle aynı saatlerde karşılaştırarak çöz.
- Adım, uyku, SpO2 geçmişi: önce Haylou Fun bağlanma/senkron kaydından komutları çıkar (`0A`, `1D 01`, `18 FA` gibi adaylar Hello Haylou'dan), sonra kontrollü test.
- Saklama yerelde; CSV ve Health Connect dışa aktarma.
- Karar: kayıtları sürekli mi dinleyelim, bağlanınca toplu mu çekelim (pil karşılaştırması ile).

### v0.5: Otomasyon (K-O)

- Tasker/intent arayüzü; kural motoru (sessiz saatler, kişiye göre titreşim, saat düğmesi olayı `12`).

### Araştırma kolu (paralel, YALNIZCA OKUMA)

| No | İş | Saate yazma |
| --- | --- | --- |
| R1 | Realtek OTA servisinin okunabilir karakteristiklerini oku (`ffd2`-`ffd5`, `ffe0`, `fff1`): çip/görüntü bilgisi, belki bank düzeni. Anlamları Realtek SDK'sından çıkar. | Yok |
| R2 | Haylou Fun ve Hello Haylou APK'larında gömülü firmware veya kadran dosyası ara | Yok |
| R3 | Find Hub (FMDN) ve OpenHaystack gereksinimlerini çıkar; RTL876x'te dönen anahtar hesabının fizibilitesi | Yok |
| R4 | Kadran akışını (`26 01` … `26 03`) Hello Haylou'nun LS05 için kullanıp kullanmadığını koddan doğrula | Yok |

Bu koldan firmware veya kadran yazmaya geçiş, ayrı bir karar ve kurtarma yolunun kanıtı gerektirir. Kullanıcı şu an donanım almak istemiyor; UART olmadan kurtarma yolu yok.

---

## 5. Açık kararlar

| Karar | Seçenekler | Kimde |
| --- | --- | --- |
| Haylou Fun bırakılacak mı | Tamamen bırak / ara sıra kullan (çakışma yönetimi gerekir) | Kullanıcı |
| Depo görünürlüğü | Herkese açık kalsın / özel (özel depoda Actions kuyruğu takılmıştı) | Kullanıcı |
| Kişisel gözlem ağı | v0.3'e ekle / sonraya bırak / hiç | Kullanıcı + ajanlar |
| Kimlik saklama | SharedPreferences (şimdiki) / Android Keystore ile şifreli | Ajanlar |
| Sağlık kayıtları | Sürekli dinleme / toplu çekme | v0.4'te ölçümle |

---

## 6. İkinci gözden geçirme: bu planda nerede yanılıyor olabiliriz

### 6.1 Planın kendi varsayımları

| Varsayım | Yanlışsa ne olur | Nasıl erken anlarız |
| --- | --- | --- |
| Haylou Fun her bağlantıda aynı `20 02` kimliğini gönderiyor, saat her seferinde `1` dönüyor | Otomatik yeniden bağlanma bir gün `4` (onay) alıp takılır | M1 dayanıklılık testinde her doğrulama sonucu loglanır |
| Saat bağlı değilken yayın yapıyor | v0.3 radar ve gözlem ağı çalışmaz | v0.3 önkoşul testi, ilk iş |
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
