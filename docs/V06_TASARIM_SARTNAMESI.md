# LS05 Controller v0.6.0: görsel tasarım şartnamesi (v3)

Durum: uygulama öncesi son metin. v2 şartname (diğer ajan) ve görseli `haylou_solar_ls05_dark_ui_dashboard.png` temel alındı; aşağıdaki düzeltmeler ve ölçülebilir kurallar eklendi.
Rol dağılımı: uygulama bu depodaki ajan; tasarım danışmanlığı ve ikinci değerlendirme diğer ajan (v0.6.0 ekran görüntüleri ve test sonuçları üzerinden).
Görsel referans birebir piksel şartnamesi değildir; hiyerarşi, bileşen ilişkileri ve renk dili için kullanılır. Görseldeki değerler örnektir.

## 0. Bağlam

- Uygulama v0.5.0'da. İşlevler: bildirim ve arama iletimi, 3 alarm, rahatsız etme, profil ve ekran ayarları, adım, nabız, uyku (saatle birebir doğrulandı), saatten kumanda.
- Bu tur yalnızca görünüm: iş mantığı, BLE kuyruğu, ayar durum modeli ve protokol değişmez. Tek istisna alarm notu (bölüm 6).
- Android framework View, yeni bağımlılık yok, minSdk 26.

## 1. v2'ye göre düzeltmeler

1. **"Başarısız" alarm durumu yoktur.** Onayı gelmeyen komut başarısız sayılmaz; saat uygulamış olabilir. Durumlar ve gösterimleri bölüm 4'te. Kırmızı alarm satırlarında kullanılmaz.
2. **Renk kaynağı şartnamedir, görseldeki palet değil.** Görseldeki #EF4444, #22C55E, #D9B35B kullanılmaz; koyu zeminde titreşir ve ikinci bir yeşil nane yeşiliyle karışır. Başarı rengi nane yeşilidir.
3. **Üst uygulama çubuğu yok.** Görseldeki "Haylou Solar LS05", menü ve dişli kaldırılır (menünün açacağı bir şey yok, dişli Ayarlar sekmesinin kopyası). Her ekranın üstü: sayfa başlığı + bağlantı kapsülü.
4. **Birincil düğme varsayılanı tonal:** `accentMintBg` zemin + `accentMint` yazı. Parlak dolu nane düğme (`accentMint` zemin, koyu yazı) ekran başına en çok bir kez ve yalnızca kritik onayda (ör. "Yeniden gönder" penceresi).
5. **Saatlik adım:** saat adımsız saatleri göndermez; eşitleme yapılmışsa boş saat gerçek 0'dır ve 0 çizilir. Hiç eşitleme yoksa grafik yerine "Henüz veri yok" yazısı.
6. **Sürüm:** bu tur v0.6.0. v2'deki v0.4.x adımları geçersiz.

## 2. Tasarım token'ları

| Token | Koyu | Açık | Kullanım |
| --- | --- | --- | --- |
| bg | #0D0F12 | #F4F5F3 | Ekran zemini |
| card | #171B20 | #FFFFFF | Kart |
| raised | #20262D | #ECEEEB | Basılı yüzey, segment dış yüzeyi, ikincil düğme |
| border | #2B323A | #DADDD9 | Gerekirse ince ayraç |
| text | #F3F4F2 | #15181C | Başlık, sayı |
| text2 | #A6AFB8 | #5B636C | Açıklama |
| muted | #7F8A95 | #8A929A | Pasif bilgi, kapalı ikon |
| accent | #7FE3C3 | #0E8A68 | Seçili, açık, ilerleme, onay |
| accentBg | #15382E | #DDF3EB | Kapsül, seçili sekme, tonal düğme |
| onAccent | #06281F | #FFFFFF | Dolu nane düğme yazısı |
| warning | #E2BC70 | #8A6200 | Bekleyen, onay gelmedi, bilinmiyor |
| warningBg | #3A3016 | #FBEFD2 | Uyarı bandı |
| danger | #E78D93 | #B3333B | Yalnızca gerçek hata, silme |
| sleepDeep | #5769CC | #4757B8 | Derin uyku |
| sleepLight | #A9B7F5 | #8FA0EE | Hafif uyku |
| heart | #F09AA5 | #C9475A | Nabız |
| switchOffTrack | #3A414A | #C9CED3 | Kapalı anahtar yolu |
| switchOffThumb | #C8CED3 | #FFFFFF | Kapalı anahtar topu |

Tek kaynak: `values/colors.xml` ve `values-night/colors.xml`. Kodda hex yok.

## 3. Ölçülebilir kurallar

**Nane yeşili yalnızca:** seçili sekme, açık anahtar, ilerleme halkası/çubuğu, "Saat onayladı" yazısı, bağlı kapsülü, ekran başına en çok bir birincil düğme. Başka yerde kullanılmaz (başlıklar, ikonlar, kart kenarları dahil).

**Yoğunluk:** Saat sekmesinde 3 alarm + rahatsız etme kartı, Xiaomi 17 Ultra'da varsayılan yazı boyutunda kaydırmadan görünür. Bunun için alarm saati 28 sp, alarm kartı iç boşluğu 14 dp, kart arası 10 dp; gerekirse durum satırı tek satır.

**Geometri:** yatay ekran boşluğu 16 dp; büyük özet kartı 24 dp köşe, diğer kartlar 20 dp, düğmeler 16 dp, kapsüller tam yuvarlak. Dokunma hedefi en az 48×48 dp. Yazılar sp.

**Tipografi:** sayfa başlığı 26 sp; kart başlığı 14 sp (text2); büyük değer 32 sp (alarm saati 28 sp); açıklama 13 sp. Ağırlık: başlık ve değerler medium, gerisi normal.

**Hareket:** yalnızca platform ripple. Gölge, gradyan, parıltı yok.

**Erişilebilirlik:** durum hiçbir zaman yalnızca renkle anlatılmaz (metin + gerekirse ikon). Sistem yazı boyutu en büyükte metin kesilmez, gerekirse satır kırılır. Anahtarlar ve sekmeler TalkBack'te durumunu söyler.

## 4. Alarm ve ayar durumları (durum modeli değişmez)

| Model durumu | Metin | Renk | İkon | Eylem |
| --- | --- | --- | --- | --- |
| IDLE | Henüz saate gönderilmedi | muted | yok | yok |
| PENDING (bağlı değil) | Bağlanınca gönderilecek | warning | saat | yok |
| PENDING/SENDING (bağlı) | Gönderiliyor… | text2 | küçük dönen gösterge | yok |
| CONFIRMED | Saat onayladı · bugün 12:09 | accent | onay | yok |
| NO_ACK | Onay gelmedi. Saatteki durum bilinmiyor. | warning | ünlem | Yeniden gönder (tonal) |
| UNKNOWN | Saat sıfırlanmış olabilir. | warning | soru | Yeniden gönder (tonal) |

Anahtarın konumu her zaman kullanıcının istediği değeri gösterir; saatin durumunu satırdaki metin söyler. "Saat onayladı" alarmın çalacağı garantisi olarak anlatılmaz.

## 5. Bileşenler (Ui.kt, tek kaynak)

Kart (büyük/normal), bölüm başlığı, satır (başlık, alt metin, sağ öğe, chevron), anahtarlı satır, anahtar (kendi çizimimiz: yol 52×32 dp, top 24 dp; framework Switch'e tint/state drawable), durum satırı (bölüm 4), kapsül, uyarı bandı, düğmeler (tonal birincil, dolu kritik, ikincil, metin, tehlikeli, ikon), segment kontrol (Sistem | Koyu | Açık), katlanır bölüm (chevron döner), alt menü (tek yüzey, 24 dp köşe, seçili sekme accentBg kapsül), ilerleme halkası, sütun grafiği, uyku şeridi, küçük nabız eğrisi.
Ekranlar yalnızca bunlarla kurulur. Yeni ekran ya da menü eklemek yeni stil gerektirmez.

## 6. Ekranlar

**Ortak:** üstte sayfa başlığı + kapsül ("Bağlı · %54"; pil bilinmiyorsa "Bağlı"; bağlı değilse nötr "Bağlanıyor…" ya da warning "Bağlı değil"). Alt menü sabit, içerik onun üstünde biter, sistem gezinme alanına girmez.

**Bugün:** büyük adım kartı (halka + toplam + hedef); saatlik adım sütunları (yalnızca içinde bulunulan saat nane); iki küçük kart: nabız (değer, ölçüm saati, gün aralığı, küçük eğri) ve uyku (toplam, gerçek evre şeridi, uykuya dalma ve uyanma saati) ile hemen altında evre açıklaması; son eşitleme + "Verileri yenile" (ikincil). Veri yoksa sahte değer ya da grafik yok. Kurulum eksikse en üstte uyarı bandı.

**Saat:** "Alarmlar" + 3 sabit yuva kartı (ikon, saat, günler · not, anahtar, durum satırı). Altında yan yana iki küçük kart: rahatsız etme (aralık, anahtar) ve saati bul (tonal "Titret"). Sonra tam genişlik "Ekran ve profil" kartı (açık kalma süresi, bileği kaldırınca aç, adım hedefi, Profil > Düzenle).
**Alarm notu (yeni, tek işlevsel ekleme):** düzenleme penceresinde isteğe bağlı kısa not (en çok 40 karakter). Not telefonda saklanır, saate gönderilmez; alarm saatinde telefon notu saate bildirim olarak iletir (açık alarmlarda, seçili günlerde). Tam zamanlı alarm izni gerekir; izin yoksa not alanı açıklamayla devre dışı.

**Bildirimler:** üst kart: bildirimleri ilet, gelen arama, cevapsız arama, bildirimden sonra titret. "Uygulamalar" + arama simgesi; liste: gerçek uygulama ikonu, ad, son bildirim zamanı, anahtar. Sistem uygulamaları katlanır grupta. En altta "Test bildirimi gönder" (tonal).

**Ayarlar:** kurulum özeti satırı ("Her şey hazır" ya da eksik sayısı; dokununca ayrıntılı liste); Tema segment kontrolü; Saat (kimlik "Kayıtlı", tara/bağlan/durdur, bağlanınca saati eşitle, saatten kumanda, firmware); Profil; "Gelişmiş ve log" katlanır, varsayılan kapalı (test düğmeleri, log kopyala/temizle, son satırlar); Hakkında (sürüm).

**Tema:** Sistem | Koyu | Açık. Seçim uygulama içinde hemen uygulanır (`AppCompat` yok; Activity yeniden kurulur, `Configuration.uiMode` üzerinden kaynak seçimi).

## 7. Kabul

- Dört ekranda aynı renk, köşe, tipografi, anahtar ve düğme sistemi.
- Bölüm 3'teki nane yeşili ve yoğunluk kuralları sağlanır.
- Altı durum metinle ayırt edilir; hiçbir durum yanlış başarı izlenimi vermez.
- En büyük sistem yazı boyutunda kesilen metin yok; açık ve koyu tema ayrı kontrol edilir.
- v0.5.0'ın işlevleri gerilemez: bildirim, arama, alarm, rahatsız etme, adım, nabız, uyku, profil, kumanda, yeniden bağlanma.
- Doğrulama Xiaomi 17 Ultra ekran görüntüleriyle; otomatik ekran görüntüsü altyapısı yok.

## 8. Uygulama sırası

1. Token'lar ve tema seçimi.
2. Ui.kt bileşenleri.
3. Dört ekran.
4. Alarm notu.
5. Derleme, ekran görüntüsü turu, diğer ajanla ikinci değerlendirme.

## 9. Ad ve simge (13 Kasım kararı)

- Uygulama adı: **Finn's Watch** (paket adı `com.che.ls05` değişmez; güncelleme eskisinin üzerine kurulur).
- Simge: kullanıcının "FW" logosu temel alınır. Yuvarlak kasa (Haylou Solar ile uyumlu), nane yeşili halka, beyaz "F", nane "W". Uyarlanabilir simge olarak vektör çizilir: arka plan katmanı düz `bg`, ön katman saat + harfler (güvenli alan 66/108 dp). Logodaki beyaz zemin ve kayış gölgeleri kullanılmaz; launcher şekli dış kesimi yapar.
