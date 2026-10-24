# Files: write a text file, append to it and read it back line by line

import os

path = "notes.txt"

with open(path, "w", encoding="utf-8") as f:
    f.write("First line\n")
    f.write("Second line\n")

with open(path, "a", encoding="utf-8") as f:
    f.write("Added later\n")

with open(path, encoding="utf-8") as f:
    for number, line in enumerate(f, start=1):
        print(number, line.rstrip())

print("Saved to", os.path.abspath(path))
print("Size:", os.path.getsize(path), "bytes")
