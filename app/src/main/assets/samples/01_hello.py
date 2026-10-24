# Hello world: print text and ask for your name

print("Hello, world!")

name = input("What's your name? ")
if name.strip() == "":
    name = "stranger"

print(f"Nice to meet you, {name}!")
print("Your name has", len(name), "letters.")
