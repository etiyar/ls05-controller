# LS05 Controller v0.1

Haylou Solar LS05 için bağımsız Android test uygulaması. Tek amacı, Haylou uygulaması olmadan saate bağlanıp konuşabildiğimizi kanıtlamak.

Teknik kaynak: `Haylou_LS05_Ajan_Devir_Notu_v4.md`.

## Kapsam

Var:
- HAYLOU adlı cihazları tarama, tek cihaza bağlanma
- GATT haritasını loglama ve iki aday komut kanalını bulma:
  - A: `55FF` servis, yaz `33F1`, dinle `33F2` (Hello Haylou'nun kullandığı)
  - B: `60FF` servis, yaz `6001`, dinle `6002` ve `61FF/6101-6102` (Haylou Fun youcy kodunun kullandığı)
- Tüm dinleme kanallarını açma (CCCD), sonra **gate yoklaması**: zararsız pil sorgusu (`03`) YALNIZCA A (`55FF/33F1`) üzerinden. B'ye otomatik geçiş kapalı (`autoFallbackToB = false`). Yanıt gelirse logda "KANIT" satırı yazılır.
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

## Derleme

1. Android Studio'da Open ile bu klasörü aç. Gradle senkronu bitsin (ilk seferde indirme yapar).
2. Telefonda Geliştirici seçenekleri > USB hata ayıklama açık olsun, kabloyla bağla.
3. Run (yeşil üçgen), cihaz olarak telefonu seç.
4. Protokol testleri: sağ tıkla `protocol/src/test/.../UteTest.kt` > Run.

## Gate testi (ilk yapılacak)

1. Saati telefona yakın tut, şarjı yeterli olsun.
2. Haylou Fun'ı durdur: Ayarlar > Uygulamalar > Haylou Fun > Durmaya zorla. Xiaomi'de "Otomatik başlatma"yı da geçici kapat, yoksa saati geri kapar.
3. LS05 Controller'ı aç, izinleri ver.
4. Tara > çıkan "Bağlan: HAYLOU SOLAR" düğmesine bas.
5. Beklenen: log'da GATT haritası, "Dinleme açıldı … status=0" satırları, "KANIT: A 55FF/33F1 komut kanalı olarak yanıt verdi", sonra `Pil %…` ve `Firmware RH192SV002066`.
6. Sonuç ne olursa olsun "Logu kopyala" ile logu gönder.

Sonuçların anlamı:
- Firmware ve pil geldi: bağımsız okuma kanıtlandı. Test bildirimine geç.
- "A (55FF/33F1) pil sorgusuna yanıt vermedi": bağlantı, CCCD aboneliği ve uygulama seviyesi yetkilendirme incelenecek. B kanalı bu testte denenmez. Eşleştirmeyi SİLME. Logu gönder; bir sonraki adım Haylou'nun bağlanırken gönderdiği ilk paketleri incelemek.
- 133 hatası veya bağlanamıyor: Haylou hâlâ saati tutuyordur. Haylou'yu tekrar durdur, Bluetooth'u kapatıp aç, tekrar dene.
- "Beklenen UUID'ler yok": GATT haritasını gönder; yazma yapılmaz.
- Logda "Saat yetki/bağlama durumu bildirdi" satırı görürsen kodunu not et; el sıkışma sorusunun ipucu.

Bildirim testi:
1. "Test bildirimi"ne bas. Durum "Aktarım tamam" olursa saat ekranına bak, "Saatte gördüm" veya "Görmedim" de.
2. "Türkçe glif testi" (`şğıöüç ŞĞİÖÜÇ`): harflerin doğru mu, kutu/boşluk mu göründüğünü not et.

Testten sonra Haylou Fun'ı normal kullanmak istersen yeniden aç; gerekirse saati orada yeniden bağla.

## Bilinen notlar

- Uzun bildirimde metin 254 bayta (127 karakter) kesilir. Orijinal uygulama 255'te keser; tek sayı son karakteri bozabildiği için 254 seçildi.
- Bildirim paketleri arasında 50 ms boşluk var. Kayıttaki akışa göre güvenli taraf; gerekirse azaltılır.
- Tip baytı her zaman 4 (mesaj). Uygulama tipleri (WhatsApp 7, Telegram 24 vb.) Hello Haylou'dan alındı, `UteAppType` içinde; saatte simgeleri test edilmedi.

## Kaynaklar

- Haylou Fun 4.0.7: `com.yingsheng.protocol.youcy` (komut haritası, `SendMsgTask` paketleme).
- Hello Haylou 3.7.19 (bağımsız geliştirici, tiborsosdevs): `c8/a3.java` özellik → kanal tablosu. LS05 ile daha önce çalıştığı kullanıcı tarafından biliniyor.
