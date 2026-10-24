# Loops and lists: for, while, append, sort and list comprehensions

fruits = ["banana", "apple", "cherry"]
fruits.append("mango")
fruits.sort()

print("Fruits:")
for index, fruit in enumerate(fruits, start=1):
    print(f"  {index}. {fruit}")

# A list comprehension builds a new list in one line.
squares = [n * n for n in range(1, 11)]
print("Squares:", squares)
print("Even squares:", [s for s in squares if s % 2 == 0])

# A while loop runs until its condition becomes false.
countdown = 5
while countdown > 0:
    print(countdown, end=" ")
    countdown -= 1
print("Go!")
