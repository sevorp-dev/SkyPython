# Charts: sine, cosine, bars and a pie with matplotlib (install "matplotlib" in Pip first)
#
# matplotlib draws charts of every kind. SkyPython ships it prebuilt for
# Android: open Pip, find "matplotlib" and tap Install. Then run this file —
# plt.show() opens the chart right away, like a window on a computer. Swipe
# between figures, save one next to this script, or share it. Back returns
# to the output; the "Figures" chips under it open them again.

import math

try:
    import matplotlib.pyplot as plt
except ModuleNotFoundError:
    print('matplotlib is not installed yet: tap "Install matplotlib" below,')
    print('or open Pip, find "matplotlib" and tap Install. Then run this again.\n')
    raise

# Figure 1: two waves and a bar chart, side by side on one figure.
xs = [i / 20 for i in range(0, 126)]  # 0 … 2π
fig, (waves, bars) = plt.subplots(2, 1, figsize=(6.4, 7.2))
fig.suptitle("Hello from SkyPython", fontsize=15, fontweight="bold")

waves.plot(xs, [math.sin(x) for x in xs], label="sin(x)", color="#f08c2e", linewidth=2.5)
waves.plot(xs, [math.cos(x) for x in xs], label="cos(x)", color="#3b82f6", linewidth=2.5)
waves.fill_between(xs, [math.sin(x) for x in xs], alpha=0.15, color="#f08c2e")
waves.axhline(0, color="#888888", linewidth=0.8)
waves.set_title("Sine and cosine")
waves.set_xlabel("x (radians)")
waves.legend(loc="lower left")
waves.grid(alpha=0.3)

days = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
steps = [6200, 8400, 7100, 9800, 5600, 12300, 10400]
colors = ["#f08c2e" if s == max(steps) else "#94a3b8" for s in steps]
bars.bar(days, steps, color=colors)
for day, value in zip(days, steps):
    bars.text(day, value + 200, f"{value / 1000:.1f}k", ha="center", fontsize=9)
bars.set_title("Steps this week")
bars.set_ylim(0, max(steps) * 1.15)
bars.grid(axis="y", alpha=0.3)

fig.tight_layout()

# Figure 2: a pie on a figure of its own (swipe to it in the viewer).
plt.figure(figsize=(5, 5))
plt.pie([45, 30, 15, 10], labels=["Python", "Kotlin", "C", "Other"], autopct="%1.0f%%",
        colors=["#f08c2e", "#3b82f6", "#22c55e", "#94a3b8"], startangle=90)
plt.title("Languages in a weekend project")

print("Showing 2 figures…")
plt.show()
print("Done. To keep a chart as a file, use plt.savefig('chart.png').")
