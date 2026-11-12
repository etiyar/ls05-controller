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
