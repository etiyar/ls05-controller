# LS05 Controller

Haylou Solar LS05 saatimi Haylou uygulamasına bağımlı olmaktan çıkarıp, üreticinin sunmadığı özelliklerle kullanmak için kişisel Android uygulaması. Saat artık resmi güncelleme almıyor; bu proje onun yerine geçiyor.

## Asıl hedefler

| Hedef | Nasıl | Aşama |
| --- | --- | --- |
| Haylou uygulaması olmadan kullanım, bulut ve hesap yok | Kendi BLE istemcimiz (UTE/Youcy protokolü) | 1-3 |
| Doğru Türkçe bildirimler, uygulamaya göre simge ve titreşim | `0F` bildirim + uygulama tipleri + titreşim komutları | 2-3 |
| Kayıp saati bulma | Bağlantı kopunca telefonun son konumu, RSSI yakınlık radarı, saati titretme | 4 |
| Sağlık verilerine sahip olma | Adım, nabız, uyku, SpO2 geçmişi; CSV ve Health Connect'e aktarma | 3-4 |
| Otomasyon | Tasker, bildirim filtreleri, kişisel kurallar | 4 |
| Özel saat kadranı | Haylou Fun'ın resmi yükleme akışı: `1a 02` / `61FF/6101` veri / `1a 03 00` (12 Kasım kaydı) | 5 |
| Find My tarzı kalabalık ağ takibi, özel firmware | Firmware'e yayın eklemek; önce güvenli yedek ve kurtarma yolu kanıtlanmalı | 6, araştırma |

Not: Gerçek Find My / Find Hub kalabalık ağı (başkasının telefonunun saati görüp konum bildirmesi) firmware değişikliği ister. Bu, kurtarma yolu olmadan tek saati bozma riski taşıdığı için en sona bırakıldı. Telefon tarafındaki "son görülen konum" ve radar bu riski taşımadan kayıp saatin çoğu senaryosunu çözer.

## Şu anki sürüm: v0.5.0 (test bekliyor)

v0.4.2'nin (dört sekmeli arayüz, ortak ayar modeli, adım, nabız ve uyku) üzerine işlevsel eklemeler. Ekran görünümü değişmedi; yeni işlevler ilgili sekmelere kart ve satır olarak eklendi.

- Bildirim metni 80 karaktere kesilir (saat 160 bayt alıyor, yaklaşık 78 karakter gösterip "..." koyuyor). Kesme emoji gibi iki birimlik karakterleri bölmez.
- Gelen arama: arayan saatte arama ekranında görünür, saat titrer; cevaplayınca, reddedince ya da arama bitince saat susar (`0D 04`). Ek izin istemez, bildirim erişimiyle çalışır. WhatsApp gibi uygulamaların internet aramaları da arama kategorisindeyse aynı yoldan gider.
- Cevapsız arama: saate bildirim olarak gider (tip 3).
- Bildirimler sekmesi: "Gelen aramalar" ve "Cevapsız aramalar" anahtarları (varsayılan açık).
- Saatten kumanda: oynat/duraklat, sonraki, önceki, ses aç/kıs ve "telefonu bul" (zil sesi en yüksek sesle ve titreşim, Durdur eylemli bildirim, 30 sn sonra kendiliğinden durur). Telefonun müzik durumu ve ses düzeyi saate gönderilir. Ayarlar sekmesinde "Saatten kumanda" anahtarı (varsayılan açık).
- Saat sekmesinde "Ekran ve profil": ekran açık kalma süresi (5, 10, 15 sn), bileği kaldırınca ekran, adım hedefi ve ayrı "Profil" penceresinde boy, kilo, yaş, cinsiyet. Bunlar saate tek pakette (`05`) gider; profil girilmeden hiçbir şey gönderilmez. İlk gönderim elle ("Saate gönder"); saat onayladıktan sonra değişiklikler kendiliğinden gider. Bugün ekranındaki adım hedefi aynı değeri kullanır.
- Uygulama son uygulamalardan kaydırıldıktan sonra: bildirim dinleyici yeniden bağlanınca saat servisi hemen başlar; servis bekleme halindeyse bekleyen bildirim yeniden bağlanmayı öne alır; uygulama açılınca servis çalışmıyorsa başlar.
- Biçimler ve güven düzeyleri: `docs/PLAN.md` bölüm 4.2.

### v0.3.0

v0.2.0'ın üzerine, 12 Kasım Haylou Fun kaydından çözülen komutlarla:

- Alarmlar (3 yuva): saat, günler, açık/kapalı. Ayar saat onay verince kaydedilir. Saatteki alarmlar telefondan okunamadığı için ilk gönderim o yuvanın üzerine yazar.
- Rahatsız etme: başlangıç, bitiş, açık/kapalı. Ayar saatin yankısıyla kaydedilir.
- Sağlık (deneysel): "Adım, nabız ve uyku geçmişini oku" dünden beri saatlik adım ve 10 dakikalık nabız kayıtlarını ister; uyku kayıtları henüz çözülmediği için ham loglanır. Saatin kendiliğinden yolladığı canlı adım ve nabız da gösterilir. Veriler yalnızca bellekte; kalıcı kayıt v0.3a'da.
- Log: canlı adım dakikada bir yazılır; hava paketindeki şehir ve profil paketi gizlenir.
- Biçimlerin ayrıntısı ve güven düzeyleri: `docs/PLAN.md` bölüm 4.1.

### v0.2.0

- Arka planda kalıcı bağlantı (ön plan servisi), uygulama kapalıyken de çalışır. Bildirim çubuğunda "LS05 saat" görünür; oradan durdurulabilir.
- Kopunca otomatik yeniden bağlanma (2, 5, 15, 30, 60 sn bekleme; saat menzile girince sistem bağlar). Her bağlantıda doğrulama.
- Doğrulama reddedilirse, saat onay isterse veya kimlik yoksa otomatik deneme durur; 3 kez yanıtsız kalırsa durur. Uygulamadan "Bağlan" ile yeniden başlatılır.
- Telefon açılınca ve uygulama güncellenince kendiliğinden başlar (kullanıcı "Durdur" demediyse).
- Telefon bildirimlerini saate iletir: uygulama listesi (mesajlaşma varsayılan açık), uygulama tipi baytı, emoji temizliği, 60 sn tekrar engeli, saat hazır değilken 10 dk / 20 bildirimlik kuyruk.
- "Saati eşitle" düğmesi (`04`). Otomatik eşitleme varsayılan kapalı; elle doğrulandıktan sonra açılır.
- Sabit imza: build-10'dan itibaren tüm sürümler aynı anahtarla imzalı, üzerine kurulur. (Önbellek 7 gün kullanılmazsa silinebilir; o zaman bir kez kaldırıp kurmak gerekir.)

## v0.1 (gate testi, tamamlandı)

Tek amacı, Haylou uygulaması olmadan saate bağlanıp konuşabildiğimizi kanıtlamak. Bu çalışmazsa yukarıdaki hiçbir hedef yapılamaz; çalışırsa her aşama bunun üzerine kurulur.

Teknik kaynak: `Haylou_LS05_Ajan_Devir_Notu_v4.md` ve v5 eki.

## Kapsam

Var:
- HAYLOU adlı cihazları tarama, tek cihaza bağlanma
- GATT haritasını loglama ve iki aday komut kanalını bulma:
  - A: `55FF` servis, `33F1` / `33F2` (Hello Haylou tablosu; bu saatte doğrulamasız yanıt vermedi, doğrulamalı hali test edilmedi)
  - B: `60FF` servis, yaz `6001`, dinle `6002` ve `61FF/6101-6102` (Haylou Fun youcy kodunun kullandığı)
- Tüm dinleme kanallarını açma (CCCD), sonra Haylou Fun'ın bağlanma sırası: `33F1` ve `34F1` okumaları, `6001`'e doğrulama (`20 02` + kimlik), `6002`'den `20 04 01`, ardından firmware (`02`) ve pil (`03`). Komut kanalı B (`60FF/6001` → `6002`), gerçek cihazda doğrulandı.
- Logda açıkça görünenler: GATT haritası, her CCCD aboneliğinin sonucu, her yazmanın sonucu (`yazma tamam` / `YAZMA HATASI`), zaman aşımları, gelen her çerçeve.
- Ardından firmware sorgusu (`02`). Otomatik gönderilen başka komut yok.
- Standart pil okuma (`180F/2A19`) çapraz kontrol için
- Test bildirimi (`0F`) ve Türkçe glif testi, yalnızca düğmeyle. Bildirim sonrası titreşim (`06 00 00 00 02 02 00 00`, kayıttaki akış) bir seçenek; varsayılan açık. Ayrıca "Sadece titreşim" düğmesi.
- Aktarım onayı ile ekranda görünme ayrı durumlar: ekran sonucu yalnızca senin "Saatte gördüm / Görmedim" onayınla kaydedilir
- Kopyalanabilir log; bildirim metinleri gizli, MAC adresi maskeli

Bilerek yok (sonraki aşamalar ve güvenlik):
- `07 00` eşleştirme kaldırma, `07` sıfırlama, `20 02` yetki anahtarı, kadran/firmware aktarımı (`56FF/34F1`): kodda hiç yok
- Otomatik yeniden bağlanma, arka plan servisi
- Sağlık senkronu, saat ayarı, alarm, kadran, firmware
- Eşleştirmeyi silme veya değiştirme

## Proje yapısı

```
protocol/   Saf Kotlin, Android'siz. UUID'ler, komutlar, 0F paketleyici, yanıt ayrıştırıcı.
            Birim testleri btsnoop kaydındaki gerçek baytlara karşı.
app/        Android: BLE katmanı (Ls05Ble.kt) ve test ekranı (MainActivity.kt).
```

## Doğrulama durumu

- `protocol` modülü Kotlin 2.0.21 ile derlendi, 8/8 test geçti. `LS05TEST123` bildirimi kayıttaki 4 paketle (3 veri + `0F FD`), titreşim komutu kayıttaki 1486 numaralı paketle bayt bayt aynı.
- `app` modülü burada Android SDK olmadığı için tam derlenemedi; yalnızca sözdizimi denetiminden geçti. Derleme ve kod güvenliği onayı bağımsız inceleme ve Android Studio derlemesiyle verilecek. İlk tam derlemeyi Android Studio yapacak. Hata çıkarsa mesajı aynen gönder.

## Kurulum notu

build-9 ve öncesi her derlemede farklı anahtarla imzalandı. build-10'dan itibaren imza sabit. v0.1.x'ten geçerken bir kez eskisini kaldır ve kimliği yeniden gir; sonraki güncellemeler üzerine kurulur.

## Derleme

1. Android Studio'da Open ile bu klasörü aç. Gradle senkronu bitsin (ilk seferde indirme yapar).
2. Telefonda Geliştirici seçenekleri > USB hata ayıklama açık olsun, kabloyla bağla.
3. Run (yeşil üçgen), cihaz olarak telefonu seç.
4. Protokol testleri: sağ tıkla `protocol/src/test/.../UteTest.kt` > Run.

## Doğrulama (v0.1.4)

Saat, bağlanan uygulamadan önce bir doğrulama ister; gelmezse komutlara cevap vermez ve yaklaşık 30 sn sonra bağlantıyı keser. Haylou Fun'ın kaydedilmiş bağlanma sırası (`haylou_notification_test.log`, kayıt 23-32):

1. `33F1` ve `34F1` yapılandırma okumaları
2. `60FF/6001`'e `20 02 <4 bayt kullanıcı kimliği>`
3. Saat `6002`'den `20 04 01` (doğrulandı) döner
4. Ancak bundan sonra pil (`03`), firmware (`02`) ve diğer komutlar

Kayıttaki handle'lar kesin olarak eşlendi: yazma `0x0022` = `6001`, bildirim `0x0024` = `6002`.

Kullanıcı kimliği kişiseldir: koda ve depoya yazılmaz. Uygulamadaki alana girilir, yalnızca telefonda saklanır, logda `20 02 XX XX XX XX` olarak maskelenir. Saat `4` dönerse ekranında onay isteyebilir; bilinmeyen bir onay verilmez.

## Gate testi (ilk yapılacak)

1. Saati telefona yakın tut, şarjı yeterli olsun.
2. Haylou Fun'ı durdur: Ayarlar > Uygulamalar > Haylou Fun > Durmaya zorla. Xiaomi'de "Otomatik başlatma"yı da geçici kapat, yoksa saati geri kapar.
3. LS05 Controller'ı aç, izinleri ver.
4. Tara > çıkan "Bağlan: HAYLOU SOLAR" düğmesine bas.
5. Beklenen: log'da GATT haritası, "Dinleme açıldı … status=0" satırları, "KANIT: saat doğrulamayı kabul etti (20 04 01)", sonra `Firmware RH192SV002066` ve `Pil %…`.
6. Sonuç ne olursa olsun "Logu kopyala" ile logu gönder.

Sonuçların anlamı:
- Firmware ve pil geldi: bağımsız okuma kanıtlandı. Test bildirimine geç.
- "Kimlik girilmedi": kimlik alanını doldur, bağlantıyı kesip yeniden bağlan.
- "Doğrulama yanıtı yok" veya "reddedildi": kimliği kontrol et. Haylou Fun açıksa kapat.
- "Saatte onay bekleniyor": saatin ekranına bak; bilinmeyen bir onayı verme. Eşleştirmeyi SİLME. Logu gönder; bir sonraki adım Haylou'nun bağlanırken gönderdiği ilk paketleri incelemek.
- 133 hatası veya bağlanamıyor: Haylou hâlâ saati tutuyordur. Haylou'yu tekrar durdur, Bluetooth'u kapatıp aç, tekrar dene.
- "Beklenen UUID'ler yok": GATT haritasını gönder; yazma yapılmaz.
- Logda "Saat yetki/bağlama durumu bildirdi" satırı görürsen kodunu not et; el sıkışma sorusunun ipucu.

Bildirim testi:
1. "Test bildirimi"ne bas. Durum "Aktarım tamam" olursa saat ekranına bak, "Saatte gördüm" veya "Görmedim" de.
2. "Türkçe glif testi" (`şğıöüç ŞĞİÖÜÇ`): harflerin doğru mu, kutu/boşluk mu göründüğünü not et.

Testten sonra Haylou Fun'ı normal kullanmak istersen yeniden aç; gerekirse saati orada yeniden bağla.

## Bilinen notlar

- Uzun bildirimde metin 80 UTF-16 birimine (160 bayt) kesilir; saat fazlasını almıyor (v0.5.0, gerçek saat testi). Orijinal uygulama 255 bayt gönderiyor.
- Bildirim paketleri arasında 50 ms boşluk var. Kayıttaki akışa göre güvenli taraf; gerekirse azaltılır.
- Tip baytı her zaman 4 (mesaj). Uygulama tipleri (WhatsApp 7, Telegram 24 vb.) Hello Haylou'dan alındı, `UteAppType` içinde; saatte simgeleri test edilmedi.

## Kaynaklar

- Haylou Fun 4.0.7: `com.yingsheng.protocol.youcy` (komut haritası, `SendMsgTask` paketleme).
- Hello Haylou 3.7.19 (bağımsız geliştirici, tiborsosdevs): `c8/a3.java` özellik → kanal tablosu. LS05 ile daha önce çalıştığı kullanıcı tarafından biliniyor.
