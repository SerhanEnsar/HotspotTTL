# icon.ico (açık, yeşil) ve icon-off.ico (kapalı, gri) üretir. Kullanım: python3 icon.py
from PIL import Image, ImageDraw

def draw(color, slash):
    S = 1024
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((40, 40, S - 40, S - 40), radius=220, fill=color)
    w = (255, 255, 255, 255)
    cx, cy = S // 2, 430
    for r, width in ((300, 70), (180, 70)):
        box = (cx - r, cy - r, cx + r, cy + r)
        d.arc(box, 140, 220, fill=w, width=width)   # sol dalgalar
        d.arc(box, -40, 40, fill=w, width=width)    # sağ dalgalar
    d.ellipse((cx - 70, cy - 70, cx + 70, cy + 70), fill=w)
    d.polygon([(cx - 35, cy + 40), (cx + 35, cy + 40), (cx + 120, S - 150), (cx - 120, S - 150)], fill=w)
    if slash:
        d.line((230, 230, S - 230, S - 230), fill=color, width=150)
        d.line((230, 230, S - 230, S - 230), fill=w, width=70)
    return im

sizes = [(16, 16), (20, 20), (24, 24), (32, 32), (40, 40), (48, 48), (64, 64), (128, 128), (256, 256)]
draw((46, 160, 67, 255), False).save("icon.ico", sizes=sizes)
draw((120, 120, 128, 255), True).save("icon-off.ico", sizes=sizes)
