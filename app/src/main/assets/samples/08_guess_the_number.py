# Game: guess the secret number between 1 and 100

import random

secret = random.randint(1, 100)
tries = 0

print("I'm thinking of a number between 1 and 100.")

while True:
    answer = input("Your guess: ")
    if not answer.strip().isdigit():
        print("Please type a whole number.")
        continue
    guess = int(answer)
    tries += 1
    if guess < secret:
        print("Too low!")
    elif guess > secret:
        print("Too high!")
    else:
        print(f"Correct! You found it in {tries} tries.")
        break
