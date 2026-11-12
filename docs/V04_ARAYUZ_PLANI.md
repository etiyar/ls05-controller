# LS05 Controller v0.4: arayüz planı (tartışma notu)

Tarih: 12 Kasım 2026. Durum: öneri, karar verilmedi. Bu not ikinci bir görüş almak için yazıldı.
Proje: Che'nin kendi Haylou Solar LS05 saati için Haylou Fun'ın yerini alan Android uygulaması.
Saat artık resmi güncelleme almıyor. Depo: `etiyar/ls05-controller`. Tek doğruluk kaynağı: `docs/PLAN.md`.

## 1. Bugüne kadar doğrulananlar (gerçek cihaz)

| Özellik | Durum |
| --- | --- |
| Bağımsız bağlantı ve doğrulama (`20 02` → `20 04 01`) | Çalışıyor |
| Bildirim iletimi, Türkçe harfler, titreşim | Çalışıyor |
| Kopma sonrası kendiliğinden yeniden bağlanma, arada gelen bildirimlerin kuyruktan iletilmesi | Geçti (12 Kasım 21:07) |
| Saat eşitleme (`04`, son bayt UTC farkı) | Çalışıyor |
| Alarm (`06`): gönderme, onay, çalma | Geçti (alarm saatte çaldı) |
| Rahatsız etme (`15`): açma, kapama, onay | Saat onayladı |
| Adım geçmişi (`0a`) ve canlı adım (`09`) | Geçti: uygulama 1447 = saat 1447 |
| Nabız geçmişi (`18 FA`), canlı nabız (`18 03`) | Okunuyor. Canlı değer çoğu zaman 0 geliyor, aynı dakikanın geçmiş kaydında değer var |
| Uyku (`1d 01`) | Henüz veri yok; ilk gece testi 12–13 Kasım gecesi |
| Hava (`11`) | Protokol çözüldü, uygulamada yok (veri kaynağı yok) |
| Kadran yükleme | Akış kaydedildi, uygulamada yok (yarıda kalma riski, karar bekliyor) |

Protokol ayrıntıları ve güven düzeyleri: `docs/PLAN.md` bölüm 4.1.

Şu anki arayüz (v0.3.0): tek uzun ekran. İçinde kurulum listesi, kimlik alanı, bağlantı düğmeleri, test düğmeleri (firmware, pil, saat, test bildirimi, Türkçe test, titreşim), alarmlar, rahatsız etme, sağlık özeti, uygulama listesi ve log var. İşlev öncelikli yapıldı; estetik ikinci aşamaya bırakılmıştı.

## 2. Amaç

Kullanıcının günlük kullandığı şeyler: alarm, rahatsız etme, bazen hava, adım, nabız, diğer sağlık verileri, kadran değiştirme, bildirimler.
Hedef: bunlara en kısa yoldan erişilen, sade bir arayüz. Ölçüt: **en az düzeltme turu ve en az hata.**

## 3. Kısıtlar (planı belirleyen)

- Geliştirme ortamında Android derlenemiyor (Maven erişimi yok). Saf Kotlin protokol modülü yerelde derlenip test ediliyor. Uygulama yalnızca GitHub Actions'ta derleniyor. Her derleme hatası bir CI turu (3–5 dk) demek.
- Her sürümü kullanıcı telefona kurup elle test ediyor. Test turu sayısı pahalı.
- Telefon: Xiaomi 17 Ultra, HyperOS (arka plan uygulamalarını kapatmaya eğilimli).
- Saatin alarmları telefondan okunamıyor (okuma komutu bulunamadı). Doğruluk kaynağı uygulamanın kendi kaydı.
- Saat en çok 3 alarm yuvası destekliyor.

## 4. Önerilen kararlar

| Konu | Öneri | Reddedilen seçenek ve nedeni |
| --- | --- | --- |
| Arayüz teknolojisi | Mevcut Android görünümleri (framework View), yeni bağımlılık yok | Jetpack Compose + Material 3: daha güzel ve durum yönetimi kolay, ama yeni derleme ayarı yerelde doğrulanamıyor; her hata bir CI turu. Bu boyutta bir uygulama için kazanç riske değmez. |
| Yapı | Alt çubukta 4 sekme: Bugün, Saat, Bildirimler, Ayarlar | Tek uzun ekran: test düğmeleri günlük kullanımı gölgeliyor |
| Alarm ekranı | 3 sabit satır, her birinde anahtar ve düzenleme | "Alarm ekle" ve gizli yuva eşlemesi: ek mantık, yeni hata kaynağı |
| Bağlantı yokken ayar | "İstenen durum / onaylanan durum" modeli: bağlanınca yalnızca farklı olan ayar gönderilir | Komut kuyruğu: eski bir ayarı sonradan gönderebilir, sıralama hatası riski |
| Onay işleme | Servis katmanında (uygulama kapalıyken de) | Şu an ekranda işleniyor: ayarı gönderip uygulamayı hemen kapatınca onay kayboluyor (mevcut hata) |
| Kurulum | Mevcut kontrol listesi Ayarlar'a taşınır; eksik izin olursa Bugün'de uyarı bandı | İlk açılış sihirbazı: yeni akış, yeni hata |
| Renk | Sabit renk + sistemin koyu modu | Duvar kâğıdından renk (Material You): HyperOS'ta öngörülemez, ekran görüntüleriyle hata ayıklamayı zorlaştırır |
| Sağlık verisi saklama | Saatten gelen ham kayıtlar günlük dosyada saklanır; tüm değerler okurken hesaplanır | Yorumlanmış veriyi saklamak: bir alanın anlamı düzelirse veri dönüştürme (migration) gerekir |
| Bitmemiş özellikler | Çalışana kadar görünmez | "Yakında" satırları: kalabalık |
| Kapsam | Arayüz işi sırasında yeni saat özelliği eklenmez | Paralel özellik: hata kaynağı karışır |

Mimari ilke: **karar mantığı arayüzden ayrılır.** Alarm farkı hesaplama, bugünkü adım toplamı, son geçerli nabız, 0 değerinin "ölçüm yok" sayılması, gece yarısı sınırı (nabız bloğu saat 0 önceki güne ait) saf Kotlin'de yazılır ve birim testiyle doğrulanır. Arayüz yalnızca hazır sonucu çizer.

## 5. Ekranlar

**Her ekranın üstü:** bağlantı durumu ve pil (ör. "Bağlı · Pil %58"), son eşitleme saati, elle yenileme.

**Bugün:** adım (hedefe göre çubuk + saatlik dağılım grafiği), son nabız ve ölçüm saati, uyku (veri çözülünce), hava (kaynak bağlanınca). Eksik izin veya kopukluk varsa üstte uyarı bandı.

**Saat:** 3 alarm satırı (saat, günler, anahtar; dokununca düzenleme penceresi), rahatsız etme satırı (aralık + anahtar), "Saati bul" (titreşim). Her satırda durum: "Gönderiliyor…", "Saate yazıldı", "Bağlanınca gönderilecek".

**Bildirimler:** ana anahtar, arama, son bildirim zamanına göre sıralı uygulama listesi, test bildirimi.

**Ayarlar:** kurulum kontrol listesi, kimlik (yalnızca "Kayıtlı"), firmware, Gelişmiş (log, test komutları).

**Kalıcı bildirim:** "Bağlı · %58 · 6.240 adım", dakikada en çok bir güncelleme.

## 6. Sıra: üç küçük sürüm

1. **v0.4.0, ekran değişmez.** Mantık ayrımı ve testleri, istenen/onaylanan durum modeli, onayın servise taşınması, ham kayıt saklama, bekleyen küçük düzeltmeler (0 nabız, `16` nabız ölçüm paketi).
2. **v0.4.1.** 4 sekme, anahtarlı alarm ve rahatsız etme satırları, test düğmelerinin Gelişmiş'e taşınması.
3. **v0.4.2.** Bugün kartları, saatlik adım grafiği (özel çizim), kalıcı bildirimde özet.

Uyku ve hava verisi çözüldükçe Bugün'e kart olarak eklenir. Kadran yükleme v0.5'e kayar.

## 7. Her sürümde 5 dakikalık sabit test

1. Bağlan, test bildirimi gönder.
2. Uygulama kapalıyken gerçek bir WhatsApp mesajı saate düşüyor mu?
3. Bir alarm gönder: "Saate yazıldı" görünüyor mu?
4. Uygulamadaki adım saatteki adımla aynı mı?
5. Bluetooth'u kapatıp aç: bağlantı kendiliğinden dönüyor mu?

## 8. Bilinen riskler

- Haylou Fun ile alarm değiştirilirse uygulama bunu bilemez. Kural: Haylou Fun kullanılmaz; kullanılırsa ayarlar uygulamadan bir kez yeniden gönderilir.
- Saat sıfırlanır veya pil tamamen biterse alarmlar silinebilir; uygulama "onaylandı" sanmaya devam eder (bkz. soru 2).
- HyperOS arka plan kısıtları arayüzden bağımsız; mevcut kontrol listesiyle izleniyor.
- Framework View ile koyu mod için `values-night` tema kaynağı gerekir (minSdk 26).

## 9. İkinci görüş istenen sorular

1. Bu kısıtlar altında framework View kararı doğru mu, yoksa Compose'un getirisi tek seferlik derleme riskine değer mi?
2. Alarmlar okunamadığına göre, her bağlantıda istenen alarm ve rahatsız etme durumunu koşulsuz yeniden göndermek mi daha güvenli (4 küçük yazma, saat sıfırlanmasına karşı dayanıklı), yoksa yalnızca farkı göndermek mi? Koşulsuz gönderimin o sırada çalan veya ertelenmiş bir alarmı bozma riski var mı?
3. Ham kayıt saklama biçimi: günlük düz metin satırları (onaltılık) yeterli mi, saklama süresi ne olmalı (öneri: 90 gün)?
4. Kalıcı bildirimi dakikada bir güncellemek HyperOS'ta sorun çıkarır mı (pil, bildirim sınırlaması)?
5. Uzun bildirimlerde saat 254 bayttan 160'ını aldığını bildiriyor (`0F FD 04 A0`). Metni 80 karakterde kesmek doğru mu, yoksa önce saatte görsel doğrulama mı yapılmalı?
6. Sıralamada eksik veya gereksiz adım görüyor musun?

## 10. Değişmeyen kurallar

- Kullanıcı kimliği, MAC adresi, bildirim içerikleri, hava şehri ve sağlık değerleri depoya ve loga girmez.
- `07 00` (eşleşme silme) ve kadran/firmware aktarımı, kurtarma yolu ve açık karar olmadan gönderilmez.
- Doğrulanmamış komut otomatik gönderilmez; her yeni komut önce tek kontrollü testten geçer.

## 11. İkinci görüş sonrası birleştirilmiş karar (12 Kasım, gece)

İkinci görüş: `LS05_v0.4_Arayuz_Inceleme_Revizyon_Onerileri.md`. Temel ilke ortak: saate uygulanmamış bir komut uygulanmış gibi gösterilmez.

### Kabul edilenler

- **Yuva başına durum makinesi:** `Boşta`, `Gönderiliyor(değer)`, `Onaylandı(değer, zaman)`, `Başarısız`, `Bilinmiyor`.
  - Saatin onayı (`06 [yuva]`) sürüm taşımıyor. Bu yüzden her yuvada aynı anda en çok bir komut yolda olur.
  - Onay gelince yoldaki değer "onaylandı" sayılır. Bu arada istenen değer değiştiyse, yenisi onaydan sonra gönderilir. Eski onay yeni isteği ezemez.
  - Zaman aşımı 5 sn. Aşılırsa `Başarısız` olur; sonraki bağlantıda ya da kullanıcı dokununca yeniden denenir.
- **Yeniden eşitleme koşulsuz değil.**
  - Bağlanınca yalnızca istenen değer onaylanandan farklıysa gönderilir.
  - Sıfırlanma belirtisi görülünce (doğrulama yanıtı `20 04 01` dışında bir değer, ya da uygulama verisinin silinmesi) tüm yuvalar `Bilinmiyor` olur. Kullanıcıya tek dokunuşla "Saate yeniden gönder" sunulur; otomatik yazılmaz.
  - Çalan veya ertelenmiş alarm testi, koşulsuz gönderim olmadığı için gerekli değil.
- **Sağlık kayıtları:** her satırda alınma zamanı, telefonun o andaki UTC farkı ve ham bayt tutulur. Cihazın ölçüm zamanı ham baytın içindedir.
  - Nabızda 0 her zaman "ölçüm yok" sayılır (canlı bir insanda gerçek değer olamaz). Adımda 0 gerçek sıfırdır.
- **Uzun bildirim sınırı önce görsel olarak ölçülür.** Gelişmiş'e "Uzunluk testi" eklenir. Numaralı bir cetvel metni gönderilir (`01 02 03 … 60`, ardından Türkçe harflerle bir tekrar), saatte görünen son numara okunur. Sınır ancak bundan sonra konur. Emoji zaten temizleniyor.
- **Kalıcı bildirim** yalnızca anlamlı değişiklikte güncellenir: bağlantı, pil, adımda en az 50 adım fark. Ayrıca en sık dakikada bir.
- **Görsel yön:** koyu lacivert yüzeyler, kırık beyaz metin, tek nane yeşili vurgu rengi; açık mod da desteklenir. Aralık 8 dp tabanlı, kart köşesi 16 dp, dokunma alanı en az 48 dp.
- **Durumlar metinle tanımlanır:** boş, yükleniyor, bağlı değil, hata ve veri yok için her ekranda ne görüneceği v0.4.1 öncesinde tabloya yazılır.
- **Ek cihaz testleri (v0.4.0):** komut sırasında Bluetooth kopması, aynı alarmı arka arkaya 3 kez değiştirme, onay gelmeden uygulamayı kapatma.

### Gerekçeli olarak reddedilen veya ertelenenler

- **Sorgulanabilir ayrı veritabanı:** reddedildi. 90 günlük veri yaklaşık 4.000 kısa satır ve her açılışta milisaniyeler içinde yeniden hesaplanır. Veritabanı şema ve dönüşüm riskini geri getirir. Ham günlük dosyalar tek kaynak. Hesaplanan özetler yalnızca silinip yeniden üretilebilen önbellek.
- **CI'da ekran görüntüsü testi:** ertelendi. Paparazzi veya Roborazzi yeni bağımlılık; emülatör yavaş ve kararsız. Görsel doğrulama kullanıcının ekran görüntüleriyle yapılır.
- **Her ekran ve durum için ayrı görsel taslak:** ertelendi. Tek sayfada 4 ekranın taslağı ve durumlar için metin tablosu yeterli.
- **Ertelenenler:** CompanionDeviceService karşılaştırması (mevcut ön plan servisi HyperOS'ta yeniden bağlanma testini geçti), veri dışa aktarma, animasyonlar, uyku ve hava kartları, kadran.
- **Yaz saati testi:** öncelik düşük, çünkü Türkiye 2016'dan beri sabit UTC+3. Birim testi ucuz olduğu için yine de yazılır.

### Tasarım bileşenleri

- Renk ve ölçüler `values` ve `values-night` kaynaklarında tutulur.
- `Ui.kt` içinde 8 yardımcı bulunur: kart, bölüm başlığı, satır, anahtarlı satır, durum etiketi, uyarı bandı, birincil düğme, ikincil düğme. Bütün ekranlar yalnızca bunlarla kurulur.
- Özel çizim yalnızca adım grafiğinde var.
- Saatlik adım grafiği uygun, çünkü saatlik toplamlar 7 kayıtta koşu + yürüme ile ve gün toplamı saatin ekranıyla doğrulandı.

### Kesinleşen sıra

1. **v0.4.0, mantık ve güvenilirlik, ekran aynı.**
   - Durum makinesi ve birim testleri.
   - Onay ve zaman aşımının servise taşınması.
   - Sıfırlanma belirtisi.
   - Ham kayıt deposu.
   - Gece yarısı ve geç kayıt testleri.
   - `16` paketi ve 0 nabız.
   - Uzunluk testi düğmesi.
2. **v0.4.1:** renk, ölçü ve 8 yardımcı, 4 sekme, durum tablosu, açık/koyu mod ve büyük yazı kontrolü.
3. **v0.4.2:** Bugün ekranı, saatlik grafik, kalıcı bildirim özeti.

## 12. Son kararlar (12 Kasım, 23:30)

- **Tek sürüm:** güvenilirlik ve yeni görünüm v0.4.0'da birlikte gelir (alarm satırları iki kez yazılmasın, tek test turu olsun). Saatlik adım grafiği ve kalıcı bildirim özeti v0.4.1'de.
- **Ortak ayar modeli:** durum modeli alarma özel değil. Saate giden her ayar (alarm, rahatsız etme, ileride hareketsizlik, dil, hedef) aynı modeli kullanır. Yeni ayar = komut üretici + onay eşleyici + ekran satırı.
- **Gecikmiş onay:** her bağlantının oturum numarası var. Başka oturumdan ya da zaman aşımından (8 sn) sonra gelen onay hiçbir komuta sayılmaz.
- **"Onay gelmedi" başarısızlık değildir:** komut uygulanmış olabilir. Otomatik yeniden gönderilmez; satırda "Yeniden gönder" çıkar. Hiç gönderilmemiş istek ise bağlanınca gönderilir.
- **İfade:** "Saat onayladı · bugün 21:19" (onayın geldiği an). Alarmın çalacağı iddia edilmez.
- **Ham kayıt:** günlük dosya, yalnızca sona ekleme, bozuk satır atlanır, aynı kayıt iki kez yazılmaz, canlı adım yazılmaz, 90 günden eski gün dosyası bütün olarak silinir. Bulut yedeği kapalı (`allowBackup=false`).
- **Uzun bildirim:** sınır konmadı. Gelişmiş'te iki cetvel testi var; sınır saatte okunan sayıya göre belirlenecek.
- **Bağlanınca otomatik okuma:** son okuma 30 dakikadan eskiyse adım ve nabız geçmişi istenir (Haylou Fun her bağlantıda istiyor). Uyku isteği yalnızca elle.
- **Katmanlar:** protokol (saf Kotlin, 47 birim testi), mantık ve depolama, ekranlar. Arayüz değişirse yalnızca ekranlar yeniden yazılır.
- **Bilinen sınır:** aynı oturumda zaman aşımından sonra elle yeniden gönderilen komutun onayı, çok gecikmiş ilk komutun onayıyla karışabilir. İki komut aynı değeri taşıdığı için zararsız.
