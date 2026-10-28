#!/usr/bin/env python3
"""Draw the cartoon wallpapers (wallpapers/files/cartoon-*.webp): flat illustrations made here, CC0.

Each scene is an SVG written from code with a fixed seed, so the pictures can be redrawn or changed. Chromium renders
them (ImageMagick's own SVG renderer has no blur), and ImageMagick encodes the WebP.

Usage: tools/cartoon_wallpapers.py [scene ...]     (all scenes when none is named)
Then run tools/wallpapers.py for the thumbnails and the index.
"""
import math
import pathlib
import random
import subprocess
import sys
import tempfile

W, H = 1920, 1080
ROOT = pathlib.Path(__file__).resolve().parent.parent / "wallpapers" / "files"


def ridge(base, amp, seed, waves=4, step=16, close_to=H):
    """A hill or mountain line across the picture, closed along the bottom."""
    rnd = random.Random(seed)
    parts = [(rnd.uniform(0.6, 1.0) / (i + 1), rnd.uniform(1.0, 2.2) * (i + 1), rnd.uniform(0, 2 * math.pi))
             for i in range(waves)]
    pts = []
    for x in range(-step, W + 2 * step, step):
        t = x / W
        y = base - amp * sum(a * math.sin(f * math.pi * t + p) for a, f, p in parts)
        pts.append(f"{x},{y:.1f}")
    return f"M-{step},{close_to} L" + " L".join(pts) + f" L{W + step},{close_to} Z"


def stars(seed, count, top=0, bottom=H * 0.6, colour="#ffffff"):
    rnd = random.Random(seed)
    out = []
    for _ in range(count):
        x, y = rnd.uniform(0, W), rnd.uniform(top, bottom)
        r = rnd.choice((0.8, 1.0, 1.2, 1.5, 1.8, 2.4))
        o = rnd.uniform(0.35, 1.0) * (1 - 0.5 * (y - top) / (bottom - top))
        out.append(f'<circle cx="{x:.0f}" cy="{y:.0f}" r="{r}" fill="{colour}" opacity="{o:.2f}"/>')
    return "\n".join(out)


def sparkle(x, y, s, colour="#fff", opacity=0.95):
    """A four-pointed twinkle."""
    d = (f"M{x},{y - s} Q{x + s * 0.12},{y - s * 0.12} {x + s},{y} Q{x + s * 0.12},{y + s * 0.12} {x},{y + s} "
         f"Q{x - s * 0.12},{y + s * 0.12} {x - s},{y} Q{x - s * 0.12},{y - s * 0.12} {x},{y - s} Z")
    return f'<path d="{d}" fill="{colour}" opacity="{opacity}"/>'


def pine(x, ground, h, colour):
    w = h * 0.42
    tiers = []
    for i in range(3):
        top = ground - h + i * h * 0.24
        bottom = top + h * 0.42
        half = w * (0.55 + i * 0.22)
        tiers.append(f'<path d="M{x},{top:.0f} L{x + half:.0f},{bottom:.0f} L{x - half:.0f},{bottom:.0f} Z"/>')
    trunk = f'<rect x="{x - h * 0.04:.0f}" y="{ground - h * 0.12:.0f}" width="{h * 0.08:.0f}" height="{h * 0.14:.0f}"/>'
    return f'<g fill="{colour}">{"".join(tiers)}{trunk}</g>'


def bird(x, y, s, colour, opacity=0.8):
    return (f'<path d="M{x - s},{y} Q{x - s / 2},{y - s * 0.55} {x},{y} Q{x + s / 2},{y - s * 0.55} {x + s},{y}" '
            f'fill="none" stroke="{colour}" stroke-width="{max(2, s / 7):.1f}" stroke-linecap="round" '
            f'opacity="{opacity}"/>')


def cloud(x, y, s, colour, opacity):
    puffs = [(0, 0, 1.0), (-0.9, 0.25, 0.7), (0.95, 0.2, 0.75), (-0.35, -0.35, 0.75), (0.45, -0.3, 0.7)]
    circles = "".join(f'<circle cx="{x + dx * s:.0f}" cy="{y + dy * s:.0f}" r="{r * s:.0f}"/>' for dx, dy, r in puffs)
    base = f'<rect x="{x - 1.6 * s:.0f}" y="{y:.0f}" width="{3.2 * s:.0f}" height="{0.75 * s:.0f}" rx="{0.37 * s:.0f}"/>'
    return f'<g fill="{colour}" opacity="{opacity}">{circles}{base}</g>'


def night_cabin():
    trees = "".join(pine(x, g, h, "#0b0e26") for x, g, h in
                    ((1180, 905, 150), (1255, 893, 205), (1330, 900, 130), (1590, 925, 175), (1660, 938, 120),
                     (180, 960, 170), (250, 975, 120), (90, 980, 140)))
    far = "".join(pine(x, g, h, "#1a1d4a") for x, g, h in
                  ((420, 772, 70), (455, 766, 92), (490, 770, 62), (760, 760, 80), (1500, 790, 74), (1540, 784, 96)))
    return f'''
<defs>
  <linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#070a24"/><stop offset="0.55" stop-color="#1c2163"/>
    <stop offset="0.85" stop-color="#4a3f88"/><stop offset="1" stop-color="#7a5aa0"/>
  </linearGradient>
  <radialGradient id="moonGlow"><stop offset="0" stop-color="#fff4c8" stop-opacity="0.55"/>
    <stop offset="1" stop-color="#fff4c8" stop-opacity="0"/></radialGradient>
  <radialGradient id="windowGlow"><stop offset="0" stop-color="#ffcf6b" stop-opacity="0.7"/>
    <stop offset="1" stop-color="#ffb347" stop-opacity="0"/></radialGradient>
  <linearGradient id="meteor" x1="0" y1="0" x2="1" y2="0">
    <stop offset="0" stop-color="#fff" stop-opacity="0"/><stop offset="1" stop-color="#fff" stop-opacity="0.9"/>
  </linearGradient>
  <filter id="soft" filterUnits="userSpaceOnUse" x="-400" y="-400" width="2720" height="1880"><feGaussianBlur stdDeviation="40"/></filter>
</defs>
<rect width="{W}" height="{H}" fill="url(#sky)"/>
<path d="M-100,620 C400,380 900,260 2100,140" stroke="#8f8fe0" stroke-width="170" opacity="0.10" fill="none" filter="url(#soft)"/>
{stars(11, 420, 0, 700)}
{sparkle(350, 160, 16)}{sparkle(1040, 110, 12)}{sparkle(820, 330, 10)}{sparkle(1750, 420, 11)}{sparkle(600, 520, 9)}
<line x1="1180" y1="150" x2="1420" y2="250" stroke="url(#meteor)" stroke-width="4" stroke-linecap="round"/>
<circle cx="1560" cy="230" r="230" fill="url(#moonGlow)"/>
<mask id="crescent"><circle cx="1560" cy="230" r="88" fill="#fff"/><circle cx="1598" cy="205" r="80" fill="#000"/></mask>
<circle cx="1560" cy="230" r="88" fill="#fff1c1" mask="url(#crescent)"/>
<path d="{ridge(760, 70, 3, 3)}" fill="#2b2a6e"/>
{far}
<path d="{ridge(840, 55, 5, 3)}" fill="#1f1f55"/>
<path d="{ridge(930, 45, 8, 2)}" fill="#13163c"/>
<g transform="translate(1420,858)">
  <circle cx="20" cy="-40" r="170" fill="url(#windowGlow)"/>
  <rect x="-70" y="-90" width="160" height="104" fill="#2a2152"/>
  <path d="M-92,-86 L10,-170 L112,-86 Z" fill="#3b2a63"/>
  <rect x="58" y="-160" width="22" height="50" fill="#2a2152"/>
  <rect x="-45" y="-62" width="44" height="40" rx="4" fill="#ffd27a"/>
  <rect x="-25" y="-62" width="4" height="40" fill="#2a2152"/><rect x="-45" y="-44" width="44" height="4" fill="#2a2152"/>
  <rect x="25" y="-55" width="38" height="69" rx="5" fill="#1b1538"/>
</g>
<path d="{ridge(990, 35, 13, 2)}" fill="#0b0e26"/>
{trees}
<g fill="#ffe28a">{"".join(f'<circle cx="{x}" cy="{y}" r="3" opacity="{o}"/>' for x, y, o in
                          ((980, 900, 0.9), (1050, 870, 0.6), (1110, 940, 0.8), (720, 950, 0.7), (640, 905, 0.5)))}</g>
'''


def beach_sunset():
    waves = []
    for i in range(14):
        y = 700 + i * i * 2.4 + i * 9
        w = 120 + i * 40
        for j in range(5):
            x = (j * 431 + i * 173) % (W + 200) - 100
            waves.append(f'<rect x="{x}" y="{y:.0f}" width="{w}" height="{3 + i * 0.5:.1f}" rx="3" '
                         f'fill="#ffd1a6" opacity="{0.10 + i * 0.012:.2f}"/>')
    glints = "".join(f'<rect x="{960 - w / 2:.0f}" y="{y}" width="{w:.0f}" height="6" rx="3" fill="#ffe2a8" '
                     f'opacity="{o:.2f}"/>' for y, w, o in
                     ((706, 300, 0.8), (724, 240, 0.65), (746, 300, 0.55), (772, 190, 0.45), (804, 250, 0.35),
                      (842, 150, 0.28), (886, 210, 0.2)))
    leaves = "".join(
        f'<path d="M0,0 Q{dx * 0.5:.0f},{dy * 0.5 - 60:.0f} {dx},{dy} Q{dx * 0.45:.0f},{dy * 0.45 - 25:.0f} 0,0 Z"/>'
        for dx, dy in ((-260, 40), (-210, 140), (-90, 190), (230, 60), (190, 160), (60, 200), (-40, -80), (120, -60)))
    return f'''
<defs>
  <linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#2a1f5c"/><stop offset="0.35" stop-color="#7a3f86"/>
    <stop offset="0.55" stop-color="#d9607a"/><stop offset="0.64" stop-color="#ffa36b"/>
  </linearGradient>
  <linearGradient id="sea" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#b8577a"/><stop offset="0.3" stop-color="#5b3a78"/><stop offset="1" stop-color="#211a45"/>
  </linearGradient>
  <radialGradient id="sunGlow"><stop offset="0" stop-color="#ffd38a" stop-opacity="0.75"/>
    <stop offset="1" stop-color="#ff9a6b" stop-opacity="0"/></radialGradient>
  <linearGradient id="sand" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#5a3354"/><stop offset="1" stop-color="#2d1c3d"/>
  </linearGradient>
</defs>
<rect width="{W}" height="{H}" fill="url(#sky)"/>
{stars(21, 90, 0, 300, "#ffe9f0")}
<circle cx="960" cy="690" r="420" fill="url(#sunGlow)"/>
<circle cx="960" cy="690" r="150" fill="#ffd27f"/>
{cloud(420, 360, 70, "#ffb3a0", 0.55)}{cloud(1480, 300, 90, "#f39ab0", 0.45)}{cloud(1180, 470, 46, "#ffc7a3", 0.6)}
{cloud(250, 520, 40, "#ffc0a0", 0.5)}
<rect y="690" width="{W}" height="{H - 690}" fill="url(#sea)"/>
{glints}
{"".join(waves)}
{bird(640, 300, 26, "#3a2350")}{bird(700, 270, 18, "#3a2350")}{bird(1290, 380, 22, "#3a2350")}{bird(1335, 360, 14, "#3a2350")}
<path d="M-20,1100 L-20,880 C300,860 620,940 860,1100 Z" fill="url(#sand)"/>
<path d="M1300,1100 C1500,960 1720,900 1940,890 L1940,1100 Z" fill="#2a1a3c"/>
<g fill="#1d1230">
  <path d="M1690,1000 C1700,860 1660,700 1580,560 L1600,552 C1690,700 1730,860 1716,1000 Z"/>
  <g transform="translate(1586,556)">{leaves}</g>
  <circle cx="1600" cy="580" r="14"/><circle cx="1576" cy="586" r="12"/>
</g>
'''


def mountains_dawn():
    layers = [(560, 150, 31, "#b9cfe8"), (640, 130, 32, "#8fb0d9"), (720, 110, 33, "#6b8fc7"),
              (800, 95, 34, "#4f6fae"), (880, 80, 35, "#3a5291"), (960, 60, 36, "#283a72"), (1040, 40, 37, "#1b2955")]
    hills = "".join(f'<path d="{ridge(b, a, s, 4 if i < 3 else 3)}" fill="{c}"/>'
                    f'<rect y="{b - 20}" width="{W}" height="120" fill="url(#mist)" opacity="{0.6 - i * 0.07:.2f}"/>'
                    for i, (b, a, s, c) in enumerate(layers))
    return f'''
<defs>
  <linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#2c3e7a"/><stop offset="0.4" stop-color="#7f8fc7"/>
    <stop offset="0.62" stop-color="#f2b8b0"/><stop offset="0.75" stop-color="#ffd9b0"/>
  </linearGradient>
  <radialGradient id="sunGlow"><stop offset="0" stop-color="#fff3d6" stop-opacity="0.9"/>
    <stop offset="1" stop-color="#ffd9b0" stop-opacity="0"/></radialGradient>
  <linearGradient id="mist" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#ffffff" stop-opacity="0"/><stop offset="0.5" stop-color="#e8eef8" stop-opacity="0.5"/>
    <stop offset="1" stop-color="#ffffff" stop-opacity="0"/>
  </linearGradient>
</defs>
<rect width="{W}" height="{H}" fill="url(#sky)"/>
{stars(41, 70, 0, 260)}
<circle cx="1320" cy="560" r="360" fill="url(#sunGlow)"/>
<circle cx="1320" cy="560" r="95" fill="#fff4dc"/>
{cloud(500, 300, 55, "#ffffff", 0.35)}{cloud(1600, 220, 40, "#ffffff", 0.3)}
{bird(760, 400, 24, "#2c3566")}{bird(810, 375, 16, "#2c3566")}{bird(860, 410, 12, "#2c3566")}
{hills}
{"".join(pine(x, g, h, "#121c3d") for x, g, h in ((120, 1040, 130), (190, 1052, 170), (260, 1046, 110),
                                                  (1720, 1040, 150), (1800, 1050, 110), (1650, 1060, 90)))}
'''


def space_planet():
    rnd = random.Random(52)
    dust = "".join(f'<circle cx="{rnd.uniform(0, W):.0f}" cy="{rnd.uniform(0, H):.0f}" r="{rnd.uniform(1, 2.6):.1f}" '
                   f'fill="{rnd.choice(("#ffffff", "#ffd9f2", "#cfe3ff"))}" opacity="{rnd.uniform(0.4, 1):.2f}"/>'
                   for _ in range(380))
    craters = "".join(f'<circle cx="{x}" cy="{y}" r="{r}" fill="#000" opacity="0.12"/>'
                      for x, y, r in ((-60, -40, 26), (40, 50, 18), (70, -70, 12), (-30, 80, 14)))
    return f'''
<defs>
  <linearGradient id="sky" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#0d0a2b"/><stop offset="0.6" stop-color="#1d1450"/><stop offset="1" stop-color="#3a1d63"/>
  </linearGradient>
  <radialGradient id="neb1"><stop offset="0" stop-color="#ff5fa2" stop-opacity="0.35"/>
    <stop offset="1" stop-color="#ff5fa2" stop-opacity="0"/></radialGradient>
  <radialGradient id="neb2"><stop offset="0" stop-color="#4fb3ff" stop-opacity="0.28"/>
    <stop offset="1" stop-color="#4fb3ff" stop-opacity="0"/></radialGradient>
  <linearGradient id="planet" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#ffcf7a"/><stop offset="0.5" stop-color="#ff8a6b"/><stop offset="1" stop-color="#9b3f7a"/>
  </linearGradient>
  <linearGradient id="ring" x1="0" y1="0" x2="1" y2="0">
    <stop offset="0" stop-color="#ffe9c2" stop-opacity="0.2"/><stop offset="0.5" stop-color="#ffe9c2"/>
    <stop offset="1" stop-color="#ffe9c2" stop-opacity="0.3"/>
  </linearGradient>
  <linearGradient id="moon" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#9fe6ff"/><stop offset="1" stop-color="#3f6fd1"/>
  </linearGradient>
  <linearGradient id="flame" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#fff2a8"/><stop offset="0.5" stop-color="#ffab4a"/><stop offset="1" stop-color="#ff5f6b" stop-opacity="0"/>
  </linearGradient>
  <radialGradient id="planetShade" cx="0.3" cy="0.28" r="0.85">
    <stop offset="0.45" stop-color="#2a0f3a" stop-opacity="0"/><stop offset="1" stop-color="#2a0f3a" stop-opacity="0.6"/>
  </radialGradient>
  <clipPath id="planetClip"><circle r="260"/></clipPath>
</defs>
<rect width="{W}" height="{H}" fill="url(#sky)"/>
<ellipse cx="420" cy="300" rx="620" ry="380" fill="url(#neb1)"/>
<ellipse cx="1600" cy="860" rx="700" ry="420" fill="url(#neb2)"/>
{dust}
{sparkle(300, 760, 18)}{sparkle(980, 140, 14)}{sparkle(1780, 200, 12)}{sparkle(720, 520, 10)}
<g transform="translate(1380,430) rotate(-18)">
  <path d="M-470,0 A470,110 0 0 1 470,0" fill="none" stroke="url(#ring)" stroke-width="34" opacity="0.8"/>
  <circle r="260" fill="url(#planet)"/>
  <g clip-path="url(#planetClip)" opacity="0.25">
    <rect x="-300" y="-150" width="600" height="38" fill="#fff"/><rect x="-300" y="-40" width="600" height="22" fill="#7a2f6b"/>
    <rect x="-300" y="60" width="600" height="46" fill="#fff"/><rect x="-300" y="160" width="600" height="20" fill="#7a2f6b"/>
      </g>
  <circle r="260" fill="url(#planetShade)"/>
  <path d="M-470,0 A470,110 0 0 0 470,0" fill="none" stroke="url(#ring)" stroke-width="34"/>
</g>
<g transform="translate(420,760)">
  <circle r="120" fill="url(#moon)"/>{craters}
</g>
<circle cx="1750" cy="900" r="38" fill="#ffd27f"/><circle cx="1762" cy="890" r="10" fill="#000" opacity="0.12"/>
<g transform="translate(860,520) rotate(38)">
  <path d="M-22,70 Q0,230 22,70 Z" fill="url(#flame)"/>
  <path d="M-34,40 L-62,90 L-30,80 Z" fill="#ff6b8a"/><path d="M34,40 L62,90 L30,80 Z" fill="#ff6b8a"/>
  <path d="M0,-110 C46,-70 46,20 32,80 L-32,80 C-46,20 -46,-70 0,-110 Z" fill="#f4f1ff"/>
  <path d="M0,-110 C22,-92 32,-72 36,-56 L-36,-56 C-32,-72 -22,-92 0,-110 Z" fill="#ff6b8a"/>
  <circle cy="-10" r="18" fill="#4fb3ff" stroke="#c9c3ef" stroke-width="7"/>
  <rect x="-8" y="70" width="16" height="16" rx="3" fill="#8d86b8"/>
</g>
<g stroke="#ffffff" stroke-linecap="round" opacity="0.35">
  <line x1="720" y1="700" x2="790" y2="610" stroke-width="3"/><line x1="680" y1="660" x2="740" y2="585" stroke-width="2"/>
</g>
'''


SCENES = {
    "cartoon-night-cabin": night_cabin,
    "cartoon-beach-sunset": beach_sunset,
    "cartoon-mountains-dawn": mountains_dawn,
    "cartoon-space-planet": space_planet,
}


def render(name, draw):
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">{draw()}</svg>'
    with tempfile.TemporaryDirectory() as tmp:
        page = pathlib.Path(tmp) / "scene.html"
        page.write_text(f"<html><body style='margin:0;overflow:hidden'>{svg}</body></html>", encoding="utf-8")
        png = pathlib.Path(tmp) / "scene.png"
        subprocess.run(["chromium", "--headless", "--no-sandbox", "--disable-gpu", "--hide-scrollbars",
                        f"--window-size={W},{H}", f"--screenshot={png}", page.as_uri()],
                       check=True, capture_output=True)
        subprocess.run(["magick", str(png), "-quality", "80", str(ROOT / f"{name}.webp")], check=True)
    print(name, (ROOT / f"{name}.webp").stat().st_size)


if __name__ == "__main__":
    for name in sys.argv[1:] or SCENES:
        render(name, SCENES[name])
