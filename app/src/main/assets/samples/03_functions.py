# Functions: parameters, default values, return values and recursion


def greet(name, greeting="Hello"):
    """Return a greeting for name."""
    return f"{greeting}, {name}!"


def average(*numbers):
    """Average of any amount of numbers."""
    if not numbers:
        return 0
    return sum(numbers) / len(numbers)


def factorial(n):
    """n! computed recursively."""
    return 1 if n <= 1 else n * factorial(n - 1)


print(greet("Ada"))
print(greet("Guido", greeting="Hi"))
print("Average:", average(3, 4, 8))
for n in range(1, 8):
    print(f"{n}! = {factorial(n)}")
