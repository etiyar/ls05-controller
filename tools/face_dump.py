#!/usr/bin/env python3
"""Kadran dosyasının (.bin / .face) tüm karelerini tek PNG sayfasına döker.

Kullanım: python3 tools/face_dump.py kadran.bin cikti.png [--scale 2]

Her satır bir öğe: solda tür, konum ve boy; sağda kareler yan yana. 0x0000 (saydam)
pikseller dama deseniyle gösterilir. Gereken: Pillow.
"""
import argparse
import struct
import sys
import zlib

from PIL import Image, ImageDraw, ImageFont

HEADER = 24
ENTRY = 24
NAMES = {
    1: "akrep", 2: "yelkovan", 3: "saniye ibresi", 4: "saat", 5: "dakika", 6: "saniye",
    8: "pil simgesi", 9: "bağlantı", 10: "önizleme", 11: "ay", 13: "gün", 15: "gün adı",
    17: "arka plan", 19: "nabız", 20: "hava", 21: "sıcaklık", 25: "adım", 27: "yıl",
    30: "kalori", 32: "adım hedefi", 33: "pil yüzdesi", 47: "mesafe",
}


def parse(b):
    if len(b) < HEADER + ENTRY:
        raise ValueError("dosya çok küçük")
    crc = struct.unpack_from("<I", b, 8)[0]
    if crc != zlib.crc32(b[HEADER:]) & 0xFFFFFFFF:
        print("uyarı: CRC tutmuyor", file=sys.stderr)
    w, h = struct.unpack_from("<HH", b, 12)
    els = []
    o = HEADER
    while o + ENTRY <= len(b):
        t, ew, off, eh, x, y = struct.unpack_from("<HHIHHH", b, o)
        if t == 0:
            break
        frames = b[o + 16]
        tail = b[o + 21:o + 24]
        els.append(dict(type=t, w=ew, h=eh, off=off, x=x, y=y, frames=max(frames, 1), tail=tail.hex()))
        o += ENTRY
        if len(els) > 64:
            break
    return w, h, els


def frame_image(b, off, w, h):
    img = Image.new("RGBA", (w, h))
    px = img.load()
    for yy in range(h):
        for xx in range(w):
            i = off + (yy * w + xx) * 2
            if i + 1 >= len(b):
                return img
            v = (b[i] << 8) | b[i + 1]
            if v == 0:
                px[xx, yy] = (0, 0, 0, 0)
            else:
                r = ((v >> 11) & 31) * 255 // 31
                g = ((v >> 5) & 63) * 255 // 63
                bl = (v & 31) * 255 // 31
                px[xx, yy] = (r, g, bl, 255)
    return img


def checker(w, h, s=6):
    img = Image.new("RGBA", (w, h), (90, 90, 90, 255))
    d = ImageDraw.Draw(img)
    for yy in range(0, h, s):
        for xx in range(0, w, s):
            if (xx // s + yy // s) % 2:
                d.rectangle([xx, yy, xx + s - 1, yy + s - 1], fill=(130, 130, 130, 255))
    return img


def main():
    ap = argparse.ArgumentParser(description="Kadran karelerini PNG sayfasına döker")
    ap.add_argument("face")
    ap.add_argument("out")
    ap.add_argument("--scale", type=int, default=1)
    a = ap.parse_args()
    b = open(a.face, "rb").read()
    sw, sh, els = parse(b)
    label_w = 210
    gap = 6
    rows = []
    for e in els:
        tiles = [frame_image(b, e["off"] + f * e["w"] * e["h"] * 2, e["w"], e["h"]) for f in range(e["frames"])]
        rows.append((e, tiles))
    sc = max(1, a.scale)
    width = label_w + max((sum(t.width * sc + gap for t in ts) for _, ts in rows), default=0) + gap
    height = sum(max(e["h"] * sc, 40) + gap * 2 for e, _ in rows) + gap
    sheet = Image.new("RGBA", (width, height), (24, 24, 24, 255))
    d = ImageDraw.Draw(sheet)
    font = None
    for name in ("DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "Arial.ttf"):
        try:
            font = ImageFont.truetype(name, 12)
            break
        except OSError:
            pass
    if font is None:
        font = ImageFont.load_default()
    y = gap
    for e, tiles in rows:
        name = NAMES.get(e["type"], "?")
        d.text((gap, y), "tür %d %s" % (e["type"], name), fill=(255, 255, 255, 255), font=font)
        d.text((gap, y + 14), "x=%d y=%d %dx%d" % (e["x"], e["y"], e["w"], e["h"]), fill=(200, 200, 200, 255), font=font)
        d.text((gap, y + 28), "%d kare, kuyruk %s" % (e["frames"], e["tail"]), fill=(200, 200, 200, 255), font=font)
        x = label_w
        for t in tiles:
            t2 = t.resize((t.width * sc, t.height * sc), Image.NEAREST) if sc > 1 else t
            bg = checker(t2.width, t2.height)
            bg.alpha_composite(t2)
            sheet.paste(bg, (x, y))
            x += t2.width + gap
        y += max(e["h"] * sc, 40) + gap * 2
    sheet.save(a.out)
    print("%s: %dx%d, %d öğe → %s" % (a.face, sw, sh, len(els), a.out))


if __name__ == "__main__":
    main()
