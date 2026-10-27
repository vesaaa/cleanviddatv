# Online wallpapers

The launcher's wallpaper gallery (Launcher settings > Wallpaper) lists these pictures under 在线壁纸 and downloads one
on OK. It reads `index.json` from the CDN (`download.aikit.stream/cleanviddatv/wallpapers/`, synced by the release
workflow when the R2 secrets are set, at each release), then GitHub (`raw.githubusercontent.com/.../main/wallpapers/`),
so a picture added here on `main` reaches TVs through GitHub at once and through the CDN with the next release.

To add one:

1. Put a 1920x1080 WebP at `files/<id>.webp` (`id`: lower case, digits and dashes). Keep it dark enough for white
   text and around 150-300 KB:
   `magick in.jpg -resize 1920x1080^ -gravity center -extent 1920x1080 -quality 66 files/<id>.webp`
2. Add it to `catalog.json` with a Chinese title, the author, the licence and where it came from. Only pictures the
   project may redistribute: CC0, CC BY, CC BY-SA or the author's own.
3. Run `tools/wallpapers.py`: it makes `thumbs/<id>.webp` and rewrites `index.json` with sizes and SHA-256s
   (CI runs `tools/wallpapers.py --check`).

Sources and licences: the `source`, `credit` and `license` of each entry in `catalog.json`; the nature photos are
from Wikimedia Commons (see also `themes/nature-calm/CREDITS.md`), `retro-*` are CC0 by REGT-URRED
(`themes/retro/CREDITS.md`), `aurora` and `glass` come from the CleanYourTV themes (MIT).
