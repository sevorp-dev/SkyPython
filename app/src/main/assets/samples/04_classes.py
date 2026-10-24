# Classes: a bank account with methods, inheritance and __str__


class Account:
    def __init__(self, owner, balance=0):
        self.owner = owner
        self.balance = balance

    def deposit(self, amount):
        self.balance += amount

    def withdraw(self, amount):
        if amount > self.balance:
            print(f"  {self.owner}: not enough money!")
            return
        self.balance -= amount

    def __str__(self):
        return f"{self.owner}: {self.balance:.2f}"


class SavingsAccount(Account):
    def __init__(self, owner, balance=0, rate=0.05):
        super().__init__(owner, balance)
        self.rate = rate

    def add_interest(self):
        self.deposit(self.balance * self.rate)


alice = Account("Alice", 100)
bob = SavingsAccount("Bob", 200)

alice.deposit(50)
alice.withdraw(500)
bob.add_interest()

for account in (alice, bob):
    print(account)
