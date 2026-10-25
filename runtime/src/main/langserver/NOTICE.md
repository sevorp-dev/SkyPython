# Language server: third-party code

`skylang_server.py` is written for SkyPython (Apache-2.0). The build
(`:runtime:langServerBundle`, see `runtime/build.gradle.kts`) packs it into
`assets/langserver/langserver.zip` together with the packages below, downloaded from PyPI and
checked against the SHA-256 hashes pinned in `runtime/langserver.properties`. They are not
covered by SkyPython's license; their own licenses apply and are shipped in the bundle
(`licenses/`, and `jedi/third_party/typeshed/LICENSE`).

| Package | Version | Wheel SHA-256 | License | Source |
|---|---|---|---|---|
| jedi | 0.20.1 | `0fb16d86c4a4c73c37ba518c77419975e30fcc620658a8d14fbb5720cdd34142` | MIT (`licenses/jedi-LICENSE.txt`) | https://pypi.org/project/jedi/0.20.1/ |
| typeshed (stdlib stubs, inside jedi) | as shipped in jedi 0.20.1 | (part of the jedi wheel) | Apache-2.0, some files MIT (`jedi/third_party/typeshed/LICENSE`) | https://github.com/python/typeshed |
| parso | 0.8.7 | `a8926eb2a1b915486941fdbd31e86a4baf88fe8c210f25f2f35ecec5b574ca1c` | MIT (`licenses/parso-LICENSE.txt`) | https://pypi.org/project/parso/0.8.7/ |
| pyflakes | 4.0.3 | `330ba92b8c1db2eb0b8f4068f6c58674e2649a99e334769aa50e3e9c5b11c23a` | MIT (`licenses/pyflakes-LICENSE.txt`) | https://pypi.org/project/pyflakes/4.0.3/ |

Modifications: none to the code. Files left out to keep the APK small: jedi's third-party
stubs (`jedi/third_party/typeshed/stubs/`, `jedi/third_party/django-stubs/`), typeshed's
`tkinter` stubs (tkinter is not in the bundled stdlib), pyflakes' test suite, and the wheels'
`.dist-info` metadata (their license files are kept under `licenses/`).
