# Online wallpapers

The launcher's wallpaper gallery (Launcher settings > Wallpaper) lists these pictures under 在线壁纸 and downloads one
on OK. It reads `index.json` from the CDN (`download.aikit.stream/cleanviddatv/wallpapers/`), then GitHub
(`raw.githubusercontent.com/.../main/wallpapers/`) when the CDN cannot be reached. A push to `main` that changes this
folder runs `.github/workflows/wallpapers.yml`, which uploads it to the CDN (and each release does too), so no release
is needed for new pictures.

To add one:

1. Put a 1920x1080 WebP at `files/<id>.webp` (`id`: lower case, digits and dashes). Keep it dark enough for white
   text and around 150-300 KB:
   `magick in.jpg -resize 1920x1080^ -gravity center -extent 1920x1080 -quality 66 files/<id>.webp`
2. Add it to `catalog.json` with a Chinese title, the author, the licence and where it came from. Only pictures the
   project may redistribute: CC0, CC BY, CC BY-SA or the author's own.
3. Run `tools/wallpapers.py`: it makes `thumbs/<id>.webp` and rewrites `index.json` with sizes and SHA-256s
   (CI runs `tools/wallpapers.py --check`).

Sources and licences: the `source`, `credit` and `license` of each entry in `catalog.json`; the nature photos are
from Wikimedia Commons (see also `themes/nature-calm/CREDITS.md`),
`glass` comes from the CleanYourTV themes (MIT).
`sky-*` and `beach-*` are from Wikimedia Commons, `earth-*` are NASA photographs (public domain, also via Commons),
and `cartoon-*` are drawn by `tools/cartoon_wallpapers.py` in this repository (CC0): change a scene there and rerun
it, then `tools/wallpapers.py`.
