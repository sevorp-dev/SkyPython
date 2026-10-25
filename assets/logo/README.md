# SkyPython logo

| File | What it is |
| --- | --- |
| `skypython-mark.svg` | The mark only (cloud, two snakes, orbit flare), transparent background, for dark surfaces |
| `skypython-logo.svg` | Mark above the "SkyPython" wordmark, transparent, for dark backgrounds |
| `skypython-logo-light.svg` | The same lockup recoloured for light backgrounds |
| `skypython-mark-512.png` | 512 × 512 render of the mark (transparent) |
| `skypython-logo-1200.png` | 1200 × 630 render of the lockup on the dark background (README, social cards) |

In the app the mark is `app/src/main/res/drawable/ic_skypython_mark.xml`. The launcher icon is built
from the same shapes (`ic_launcher_foreground.xml`, `ic_launcher_background.xml`, `ic_launcher_monochrome.xml`).

## Colours

| Role | Dark background | Light background |
| --- | --- | --- |
| Background | `#12161C` | `#FFFFFF` |
| Cloud outline | `#C9D4E0` | `#7D8A9A` |
| Light snake | `#EEF2F6` | `#1E2632` |
| Orange (cloud right side, snake, flare) | `#F28C38` | `#E8812F` |
| Wordmark "Sky" | `#FFFFFF` | `#1E2632` |
| Wordmark "Python" | `#F28C38` | `#D06A17` |

The wordmark is set in Poppins Bold (SIL Open Font License) and converted to outlines, so no font is needed to display it.

## Usage

- Keep clear space around the logo of at least the height of the cloud's left bump (about a quarter of the mark's width).
- Smallest size: the mark reads down to 24 px; the full lockup should be at least 120 px wide.
- Don't recolour, stretch, rotate, add shadows or glows, or rearrange the parts.
- On busy or mid-tone backgrounds, put the logo on a solid `#12161C` or white panel.

## License and origin

The logo is original artwork made for SkyPython and is released under the project's license, Apache-2.0.
It is **not** the Python Software Foundation's "two snakes" logo and isn't derived from it. The two snakes
here are round hooks of even width with circular heads and tapering tails, and each one hooks into the
other's curve to form an "S". The PSF logo uses flat blocky shapes with square heads arranged in a cross.
"Python" is a trademark of the Python Software Foundation. SkyPython is not affiliated with or endorsed by the PSF.
