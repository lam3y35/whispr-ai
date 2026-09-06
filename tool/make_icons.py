"""Generate Whispr AI launcher icons, Windows .ico, and adaptive-icon assets.

Design: the sober end of the 1980s field recorder.
  - dark warm face (#1C1B18), thin panel edge
  - cream voice waveform bars (#EDE6D6)
  - one red REC dot (#E74C3C) with a darker ring
Three elements, readable at 16 px.
"""
from PIL import Image, ImageDraw
import os

FACE = (28, 27, 24, 255)        # #1C1B18
EDGE = (58, 55, 47, 255)        # #3A372F
INK = (237, 230, 214, 255)      # #EDE6D6
INK_DIM = (143, 138, 124, 255)  # #8F8A7C
REC_RED = (231, 76, 60, 255)    # #E74C3C
REC_DARK = (192, 57, 43, 255)   # #C0392B

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")


def rounded_rect(draw, box, radius, fill):
    draw.rounded_rectangle(box, radius=radius, fill=fill)


def icon_face(size: int) -> Image.Image:
    """The square recorder face used as the adaptive-icon background."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    m = size // 16
    rounded_rect(d, (m, m, size - m, size - m), radius=size // 8, fill=FACE)
    # thin seam just inside the edge — visible "two pieces of hardware"
    e = max(1, size // 128)
    d.rounded_rectangle(
        (m + e * 4, m + e * 4, size - m - e * 4, size - m - e * 4),
        radius=size // 8 - e * 4,
        outline=EDGE, width=e * 2,
    )
    return img


def draw_waveform(d, cx, cy, unit):
    """5 cream bars, symmetric heights, like a level meter mid-word."""
    heights = [0.9, 1.6, 2.4, 1.6, 0.9]
    bw = unit * 0.62          # bar width
    gap = unit * 0.55         # gap between bars
    total = len(heights) * bw + (len(heights) - 1) * gap
    x = cx - total / 2
    for h in heights:
        bh = unit * h
        top = cy - bh / 2
        d.rounded_rectangle(
            (x, top, x + bw, top + bh), radius=bw / 2, fill=INK
        )
        x += bw + gap


def draw_rec_dot(d, cx, cy, r):
    """Red REC dot with its darker ring, slightly right of center."""
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=REC_DARK)
    r2 = r * 0.72
    d.ellipse((cx - r2, cy - r2, cx + r2, cy + r2), fill=REC_RED)


def foreground(size: int) -> Image.Image:
    """Adaptive-icon foreground: waveform + REC dot on transparent."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cx, cy = size / 2, size / 2
    unit = size * 0.085
    # waveform sits slightly above center; REC dot below-right of it
    draw_waveform(d, cx, cy - size * 0.10, unit)
    draw_rec_dot(d, cx + size * 0.005, cy + size * 0.14, size * 0.055)
    return img


def legacy(size: int) -> Image.Image:
    """Full-square legacy icon: face + artwork, no adaptive cropping."""
    img = icon_face(size)
    d = ImageDraw.Draw(img)
    cx, cy = size / 2, size / 2
    unit = size * 0.085
    draw_waveform(d, cx, cy - size * 0.10, unit)
    draw_rec_dot(d, cx + size * 0.005, cy + size * 0.14, size * 0.055)
    return img


MIPMAPS = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def main() -> None:
    # Legacy launcher icons (still used by some launchers and the app drawer).
    for dpi, px in MIPMAPS.items():
        out = os.path.join(RES, f"mipmap-{dpi}")
        os.makedirs(out, exist_ok=True)
        legacy(px).save(os.path.join(out, "ic_launcher.png"))
        # foreground for adaptive icons; background is a color resource
        foreground(px * 2).save(os.path.join(out, "ic_launcher_foreground.png"))
        print(f"mipmap-{dpi}: {px}px + foreground")

    # Windows .ico with a sensible size ladder. Lands directly in the C# app
    # project, which references it via <ApplicationIcon>.
    ico_sizes = [16, 24, 32, 48, 64, 128, 256]
    base = 256
    img = legacy(base)
    ico_path = os.path.join(ROOT, "windows", "src", "Murmur.App", "app.ico")
    os.makedirs(os.path.dirname(ico_path), exist_ok=True)
    img.save(ico_path, sizes=[(s, s) for s in ico_sizes])
    print("windows/app.ico:", ico_sizes)

    # A 512 png for the store/repo header.
    legacy(512).save(os.path.join(ROOT, "assets", "icon-512.png"))
    print("assets/icon-512.png")


if __name__ == "__main__":
    main()
