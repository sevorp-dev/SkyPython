# Draw a picture: a fractal saved as a PNG, no packages needed
#
# A PNG file is a short header and the pixel rows squeezed with zlib, so the
# standard library is enough to write one. When the script ends, tap the
# picture's name under the output to see it.

import struct
import zlib

WIDTH, HEIGHT = 320, 240
MAX_STEPS = 40


def colour(steps):
    """A colour for how fast a point escapes: dark blue far away, orange near the set."""
    if steps == MAX_STEPS:
        return (10, 12, 20)  # inside the set
    far, near = (18, 30, 60), (255, 160, 60)
    t = (steps / MAX_STEPS) ** 0.5
    return tuple(int(a + (b - a) * t) for a, b in zip(far, near))


def mandelbrot(x, y):
    """How many steps z = z*z + c takes to leave the circle of radius 2."""
    c = complex(x, y)
    z = 0j
    for step in range(MAX_STEPS):
        z = z * z + c
        if abs(z) > 2:
            return step
    return MAX_STEPS


def png_chunk(kind, data):
    """One PNG chunk: length, type, data and a CRC check."""
    body = kind + data
    return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)


def save_png(path, width, height, rows):
    """Writes rows of (r, g, b) pixels as an 8-bit RGB PNG."""
    raw = bytearray()
    for row in rows:
        raw.append(0)  # filter type 0: the row as it is
        for r, g, b in row:
            raw += bytes((r, g, b))
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(png_chunk(b"IHDR", header))
        f.write(png_chunk(b"IDAT", zlib.compress(bytes(raw), 9)))
        f.write(png_chunk(b"IEND", b""))


rows = []
for j in range(HEIGHT):
    y = (j / HEIGHT - 0.5) * 2.4
    rows.append([colour(mandelbrot((i / WIDTH) * 3.2 - 2.2, y)) for i in range(WIDTH)])
    if j % 40 == 0:
        print(f"Drawing... {100 * j // HEIGHT}%")

save_png("mandelbrot.png", WIDTH, HEIGHT, rows)
print(f"Saved mandelbrot.png ({WIDTH}x{HEIGHT})")
