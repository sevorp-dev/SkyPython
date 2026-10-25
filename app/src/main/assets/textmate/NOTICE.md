# Third-party TextMate assets

Files in this directory are bundled third-party data used for syntax
highlighting. They are not covered by SkyPython's Apache-2.0 license; their
own licenses apply.

| File | Source | License |
|---|---|---|
| `python/syntaxes/python.tmLanguage.json` | MagicPython grammar as converted by VS Code: https://raw.githubusercontent.com/microsoft/vscode/main/extensions/python/syntaxes/MagicPython.tmLanguage.json (upstream: https://github.com/MagicStack/MagicPython, commit 7d0f2b22a5ad8fccbd7341bc7b7a715169283044). Unmodified. | MIT — `python/LICENSE-MagicPython.txt` (MagicStack Inc.), `python/LICENSE-vscode.txt` (Microsoft) |
| `python/language-configuration.json` | https://raw.githubusercontent.com/microsoft/vscode/main/extensions/python/language-configuration.json. Unmodified. | MIT — `python/LICENSE-vscode.txt` |
| `skypython-dark.json` | Derived from VS Code "Dark (Visual Studio)" + "Dark+" themes: https://raw.githubusercontent.com/microsoft/vscode/main/extensions/theme-defaults/themes/dark_vs.json and https://raw.githubusercontent.com/microsoft/vscode/main/extensions/theme-defaults/themes/dark_plus.json. Modifications: comments stripped, the two files merged into one (`include` resolved), editor and token colors changed to SkyPython's night-sky palette (background `#0B1230`). | MIT — `python/LICENSE-vscode.txt` |
| `languages.json` | Written for SkyPython (Sora Editor `GrammarRegistry` format). | Apache-2.0 |

Downloaded 2026-10-24.
