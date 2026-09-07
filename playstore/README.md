# Play Store listing assets

These are **Google Play Console** assets, not build artifacts. They are uploaded
manually in the Play Console and are **not** produced or published by the
`release.yml` workflow.

## `ic_launcher-playstore-512.png`

- **What:** The Play Store "hi-res icon" — 512×512, 32-bit PNG (RGBA).
- **Where it goes:** Play Console → your app → **Grow → Store presence → Main
  store listing → App icon**. (Also part of the initial-listing checklist in the
  release plan's Prerequisite P6.)
- **How it was derived:** Rendered from the vector source of record
  `docs/design/mercury-icon.svg` (the helmet glyph on its `#18191e` tile) at
  1024×1024 and LANCZOS-downscaled to 512. The same render feeds the iOS
  `AppIcon1024.png`, so store listings and installed icons match exactly. Do not
  hand-edit; regenerate from the SVG if the brand art changes.

To regenerate:

```bash
python3 - <<'PY'
import cairosvg, io
from PIL import Image
png = cairosvg.svg2png(url='docs/design/mercury-icon.svg', output_width=1024, output_height=1024)
Image.open(io.BytesIO(png)).convert('RGBA').resize((512, 512), Image.LANCZOS) \
    .save('playstore/ic_launcher-playstore-512.png')
PY
```

## Related launcher icon files (in `app/src/main/res/`)

- `mipmap-anydpi/ic_launcher.xml`, `ic_launcher_round.xml` — adaptive icon
  (background + foreground + **monochrome** themed-icon layer).
- `drawable/ic_launcher_foreground.xml`, `ic_launcher_background.xml` — adaptive
  foreground (inset bitmap of `drawable-nodpi/mercury_launcher_art.png`, the
  glyph alone on transparency) and the `#18191e` background.
- `drawable-nodpi/ic_launcher_monochrome.png` — Android 13+ themed-icon
  silhouette, sized inside the 66dp safe zone.
- `mipmap-*/ic_launcher*.webp` — legacy raster fallbacks (mdpi→xxxhdpi).
