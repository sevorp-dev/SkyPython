# Random and math: dice rolls, statistics and a bit of geometry

import math
import random
import statistics

rolls = [random.randint(1, 6) for _ in range(20)]
print("Dice rolls:", rolls)
print("Mean:", statistics.mean(rolls))
print("Most common:", statistics.mode(rolls))

print("Random pick:", random.choice(["rock", "paper", "scissors"]))

radius = 3
print(f"Circle area (r={radius}): {math.pi * radius ** 2:.2f}")
print("Square root of 2:", math.sqrt(2))
print("Hypotenuse of 3 and 4:", math.hypot(3, 4))
print("Greatest common divisor of 84 and 36:", math.gcd(84, 36))
