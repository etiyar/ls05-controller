#!/usr/bin/env python3
"""
Finn's Watch kadran üretici.

Temel bir LS05 kadran dosyasını (ör. Haylou Analog) alır, yapısını aynen korur;
yalnızca arka plan (17), önizleme (10) ve isteğe bağlı saniye ibresi (3) resimlerini değiştirir,
CRC32'yi yeniden hesaplar. Temel dosya repoda yoktur (üreticiye ait).

Her kadrana ayrı kimlik verilir (dosyanın ilk 2 baytı, u16 LE; CRC'ye girmez). Saat bu kimliği
1a 01 yanıtında bildirir; uygulama galerideki "Saatte" işaretini buna göre koyar. Kimlikler
--id ile verilen değerden (varsayılan 0xF100) başlayıp birer artar. Uygulamanın derlediği .tbwf
kadranları 0xF000-0xFFFE aralığında; çakışmasın diye 0xF100'den başlanır.

Kullanım: face_builder.py [--id 0xF100] TEMEL.bin CIKTI_KLASORU
"""
import argparse
import math
import struct
import sys
import zlib
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

S = 240
K = 4  # süper örnekleme
MINT = (127, 227, 195)
MINT_DIM = (38, 74, 63)
GRAY = (154, 163, 173)
DIM = (78, 86, 96)
FAINT = (30, 34, 39)
WHITE = (236, 239, 241)
FONT = "/usr/share/fonts/opentype/inter/"


def rgb565_be(im):
    out = bytearray()
    for r, g, b in im.convert("RGB").get_flattened_data():
        v = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)
        out += bytes(((v >> 8) & 0xFF, v & 0xFF))
    return bytes(out)


def from565(d, off, w, h):
    im = Image.new("RGB", (w, h))
    im.putdata([(((v >> 11) & 31) * 255 // 31, ((v >> 5) & 63) * 255 // 63, (v & 31) * 255 // 31)
                for v in (d[i] << 8 | d[i + 1] for i in range(off, off + w * h * 2, 2))])
    return im


def elements(d):
    out, o = {}, 0x18
    while True:
        t, w, off, h = struct.unpack("<HHIH", d[o:o + 10])
        if t == 0:
            return out
        x, y = struct.unpack("<HH", d[o + 10:o + 14])
        out[t] = dict(w=w, h=h, off=off, x=x, y=y, n=d[o + 16])
        o += 24


def canvas():
    im = Image.new("RGB", (S * K, S * K), (0, 0, 0))
    return im, ImageDraw.Draw(im)


def font(name, px):
    return ImageFont.truetype(FONT + name, px * K)


def finish(im):
    im = im.resize((S, S), Image.LANCZOS)
    # Daire dışı tam siyah (yuvarlak ekran).
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, S - 1, S - 1), fill=255)
    black = Image.new("RGB", (S, S), (0, 0, 0))
    return Image.composite(im, black, mask)


def pill(dr, cx, cy, w, h, color, width=1.2):
    dr.rounded_rectangle(((cx - w / 2) * K, (cy - h / 2) * K, (cx + w / 2) * K, (cy + h / 2) * K),
                         radius=h / 2 * K, outline=color, width=int(width * K))


def ticks(dr, every=5, major=MINT, minor=DIM, r_out=114, len_major=10, len_minor=4, w_major=3, w_minor=1.4,
          dots=False):
    c = S / 2
    for i in range(60):
        if i % every and not dots:
            continue
        a = math.radians(i * 6 - 90)
        is_major = i % 15 == 0
        if dots:
            if i % 5:
                continue
            rr = 2.6 if is_major else 1.5
            x, y = c + (r_out - 4) * math.cos(a), c + (r_out - 4) * math.sin(a)
            dr.ellipse(((x - rr) * K, (y - rr) * K, (x + rr) * K, (y + rr) * K), fill=major if is_major else minor)
            continue
        ln = len_major if is_major else len_minor
        x1, y1 = c + r_out * math.cos(a), c + r_out * math.sin(a)
        x2, y2 = c + (r_out - ln) * math.cos(a), c + (r_out - ln) * math.sin(a)
        dr.line((x1 * K, y1 * K, x2 * K, y2 * K), fill=major if is_major else minor,
                width=int((w_major if is_major else w_minor) * K))


def steps_icon(dr, cx, cy, color):
    # İki küçük ayak izi.
    for dx, dy in ((-3, 1), (3, -1)):
        x, y = cx + dx, cy + dy
        dr.ellipse(((x - 2.2) * K, (y - 3.6) * K, (x + 2.2) * K, (y + 2.4) * K), fill=color)
        dr.ellipse(((x - 1.6) * K, (y + 3.2) * K, (x + 1.6) * K, (y + 5.6) * K), fill=color)


def fw(dr, cx, cy, size, f_color, w_color):
    f = font("InterDisplay-ExtraBoldItalic.otf", size)
    wf = dr.textlength("F", font=f)
    ww = dr.textlength("W", font=f)
    x0 = cx * K - (wf + ww) / 2
    dr.text((x0, cy * K), "F", font=f, fill=f_color, anchor="lm")
    dr.text((x0 + wf - size * K * 0.04, cy * K), "W", font=f, fill=w_color, anchor="lm")


def info_boxes(dr, color=DIM, icon=MINT):
    pill(dr, 121, 81, 54, 19, color)       # tarih: gün adı (98,75) + rakamlar (127,75)
    # Adım: simge kutunun içinde solda; rakamlar 104'ten sola yaslı başlar, kısa sayı da düzgün durur.
    pill(dr, 118, 168, 64, 19, color)
    steps_icon(dr, 95, 167, icon)


def design_minimal():
    im, dr = canvas()
    ticks(dr, every=5, major=MINT, minor=DIM)
    info_boxes(dr)
    fw(dr, 121, 206, 14, GRAY, MINT)
    return finish(im)


def design_numbers():
    im, dr = canvas()
    c = S / 2
    dr.ellipse((2 * K, 2 * K, (S - 2) * K, (S - 2) * K), outline=MINT, width=int(1.6 * K))
    ticks(dr, dots=True, major=MINT, minor=DIM, r_out=110)
    f = font("InterDisplay-Medium.otf", 22)
    for n, a in ((12, -90), (3, 0), (6, 90), (9, 180)):
        r = 86
        x, y = c + r * math.cos(math.radians(a)), c + r * math.sin(math.radians(a))
        dr.text((x * K, y * K), str(n), font=f, fill=WHITE, anchor="mm")
    info_boxes(dr)
    return finish(im)


def design_logo():
    im, dr = canvas()
    dr.ellipse((3 * K, 3 * K, (S - 3) * K, (S - 3) * K), outline=MINT, width=int(2.2 * K))
    ticks(dr, every=15, major=MINT, minor=DIM, r_out=108, len_major=8, w_major=3)
    fw(dr, 121, 46, 24, WHITE, MINT)
    info_boxes(dr)
    return finish(im)


DESIGNS = {
    "FW Minimal": design_minimal,
    "FW Sayılar": design_numbers,
    "FW Logo": design_logo,
}


def recolor_second(hand):
    """Camgöbeği saniye ibresini naneye çevirir; parlaklığı korur."""
    out = []
    for r, g, b in hand.get_flattened_data():
        m = max(r, g, b)
        if m < 24 or (b < 100 and g < 100):
            out.append((r, g, b))
        else:
            k = m / 255
            out.append((int(MINT[0] * k), int(MINT[1] * k), int(MINT[2] * k)))
    im = Image.new("RGB", hand.size)
    im.putdata(out)
    return im


def overlay(base, layer, angle):
    rot = layer.rotate(-angle, resample=Image.BICUBIC, center=(S / 2, S / 2))
    mask = rot.convert("L").point(lambda v: 255 if v > 18 else 0)
    base.paste(rot, (0, 0), mask)


def sprite(d, e, i):
    return from565(d, e["off"] + i * e["w"] * e["h"] * 2, e["w"], e["h"])


def preview(d, els, bg, second):
    im = bg.copy()
    # 10:08:36, Cuma 28, 4.856 adım (önizleme için örnek).
    wk = sprite(d, els[15], 5)
    im.paste(wk, (els[15]["x"], els[15]["y"]))
    for i, ch in enumerate("28"):
        im.paste(sprite(d, els[13], 3 + int(ch)), (els[13]["x"] + i * els[13]["w"], els[13]["y"]))
    for i, ch in enumerate("4856"):
        im.paste(sprite(d, els[25], 3 + int(ch)), (els[25]["x"] + i * els[25]["w"], els[25]["y"]))
    overlay(im, from565(d, els[1]["off"], S, S), (10 + 8 / 60) * 30)
    overlay(im, from565(d, els[2]["off"], S, S), 8 * 6)
    overlay(im, second, 36 * 6)
    return im


DAYS_TR = ["Paz", "Pzt", "Sal", "Çar", "Per", "Cum", "Cmt"]


def weekday_sprites(e, color=WHITE):
    """Gün adı kareleri (Pazar ilk), saatin hücresine ortalı. Siyah zemin saydam sayılır."""
    out = bytearray()
    f = ImageFont.truetype(FONT + "Inter-SemiBold.otf", 10)
    for name in DAYS_TR:
        im = Image.new("RGB", (e["w"], e["h"]), (0, 0, 0))
        ImageDraw.Draw(im).text((e["w"] / 2, e["h"] / 2), name, font=f, fill=color, anchor="mm")
        out += rgb565_be(im)
    return bytes(out)


def build(base: bytes, bg: Image.Image, mint_second=True, turkish_days=True, face_id=None):
    d = bytearray(base)
    if face_id is not None:
        struct.pack_into("<H", d, 0, face_id)
    els = elements(d)
    if turkish_days and 15 in els and els[15]["n"] == 7:
        e = els[15]
        d[e["off"]:e["off"] + e["w"] * e["h"] * 2 * 7] = weekday_sprites(e)
    sec = from565(d, els[3]["off"], S, S)
    if mint_second:
        sec = recolor_second(sec)
        d[els[3]["off"]:els[3]["off"] + S * S * 2] = rgb565_be(sec)
    d[els[17]["off"]:els[17]["off"] + S * S * 2] = rgb565_be(bg)
    pv = preview(d, els, bg, sec)
    d[els[10]["off"]:els[10]["off"] + S * S * 2] = rgb565_be(pv)
    struct.pack_into("<I", d, 8, zlib.crc32(bytes(d[24:])) & 0xFFFFFFFF)
    assert len(d) == len(base)
    return bytes(d), pv


def face_id(text):
    v = int(text, 0)
    if not 1 <= v <= 0xFFFE:
        raise argparse.ArgumentTypeError("kimlik 1..0xFFFE aralığında olmalı")
    return v


def main():
    ap = argparse.ArgumentParser(description="LS05 kadran üretici")
    ap.add_argument("base", help="temel kadran dosyası (.bin)")
    ap.add_argument("out", help="çıktı klasörü")
    ap.add_argument("--id", type=face_id, default=0xF100,
                    help="ilk kadranın kimliği, sonrakiler birer artar (varsayılan 0xF100)")
    args = ap.parse_args()
    base = Path(args.base).read_bytes()
    assert struct.unpack("<I", base[8:12])[0] == zlib.crc32(base[24:]) & 0xFFFFFFFF, "temel dosya CRC tutmuyor"
    if args.id + len(DESIGNS) - 1 > 0xFFFE:
        sys.exit("kimlik aralığı 0xFFFE'yi aşıyor")
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    pvs = []
    for i, (name, fn) in enumerate(DESIGNS.items()):
        fid = args.id + i
        data, pv = build(base, fn(), face_id=fid)
        print(f"{name}.bin: kimlik 0x{fid:04X}")
        (out / f"{name}.bin").write_bytes(data)
        pv.save(out / f"{name}.png")
        pvs.append((name, pv))
    sheet = Image.new("RGB", (len(pvs) * 260 + 20, 300), (13, 15, 18))
    dr = ImageDraw.Draw(sheet)
    f = ImageFont.truetype(FONT + "Inter-Medium.otf", 16)
    for i, (name, pv) in enumerate(pvs):
        sheet.paste(pv, (20 + i * 260, 20))
        dr.text((20 + i * 260 + 120, 280), name, font=f, fill=GRAY, anchor="mm")
    sheet.save(out / "secenekler.png")


if __name__ == "__main__":
    main()
