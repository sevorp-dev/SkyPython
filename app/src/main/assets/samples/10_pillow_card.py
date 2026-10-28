# Picture card: draw a card with Pillow and open it in the viewer (install "pillow" in Pip first)
#
# Pillow is a library for working with images. SkyPython ships it prebuilt
# for Android: open Pip, find "pillow" and tap Install. Then run this file —
# the finished card appears as a chip under the output; tap it to view.

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    raise SystemExit('Pillow is not installed yet: open Pip, find "pillow" and tap Install.')

W, H = 720, 400
card = Image.new("RGB", (W, H))
draw = ImageDraw.Draw(card)

# A dark-to-orange gradient, one line at a time.
for y in range(H):
    t = y / (H - 1)
    color = (int(30 + 212 * t), int(31 + 109 * t), int(34 + 22 * t))
    draw.line([(0, y), (W, y)], fill=color)

# A few "stars".
for x, y, r in [(80, 70, 4), (200, 40, 3), (620, 90, 5), (540, 50, 3)]:
    draw.ellipse([x - r, y - r, x + r, y + r], fill="white")

title = ImageFont.load_default(56)
small = ImageFont.load_default(28)
draw.text((48, 150), "Hello from SkyPython", font=title, fill="white")
draw.text((50, 240), "Drawn on a phone with Pillow", font=small, fill=(255, 236, 214))
draw.rounded_rectangle([20, 20, W - 20, H - 20], radius=28, outline="white", width=3)

card.save("card.png")
card.save("card.jpg", quality=90)
print(f"Saved card.png and card.jpg ({W}x{H})")
