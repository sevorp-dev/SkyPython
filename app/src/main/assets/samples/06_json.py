# JSON: turn Python data into JSON text, save it and load it back

import json

contacts = [
    {"name": "Ada", "phone": "555-0100", "tags": ["math", "code"]},
    {"name": "Linus", "phone": "555-0199", "tags": ["kernel"]},
]

text = json.dumps(contacts, indent=2)
print("As JSON:")
print(text)

with open("contacts.json", "w", encoding="utf-8") as f:
    json.dump(contacts, f, indent=2)

with open("contacts.json", encoding="utf-8") as f:
    loaded = json.load(f)

print()
for person in loaded:
    print(f"{person['name']} ({', '.join(person['tags'])}): {person['phone']}")
