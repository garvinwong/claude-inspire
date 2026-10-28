# Third-party components / 第三方组件

Nothing below is copied into this repository except the DejaVu font and the Gradle wrapper; the rest are fetched by pip or Gradle at build/install time. The Android APK you build bundles the Android libraries listed here.

| Component | License | How it is used |
|---|---|---|
| [DejaVu Sans Mono](https://dejavu-fonts.github.io/) | Bitstream Vera / DejaVu license — see `licenses/DejaVu-Fonts-LICENSE.txt` | Bundled font for the Android terminal view |
| [Gradle wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html) (`android/gradle/wrapper/gradle-wrapper.jar`) | Apache-2.0 | Builds the Android app |
| [AndroidX](https://developer.android.com/jetpack/androidx) (Activity, Compose, Lifecycle, Biometric, Fragment) | Apache-2.0 | Android app dependencies |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache-2.0 | Android app dependency |
| [commonmark-java](https://github.com/commonmark/commonmark-java) (+ GFM tables / strikethrough extensions) | BSD-2-Clause | Markdown rendering in the Android app |
| [cryptography](https://github.com/pyca/cryptography) | Apache-2.0 or BSD-3-Clause | Hub: end-to-end encryption of messages and attachments |
| [pyte](https://github.com/selectel/pyte) | LGPL-3.0 | Hub: renders the terminal view (installed with pip, not vendored) |
| [qrcode](https://github.com/lincolnloop/python-qrcode) | BSD | `palm pair --qr` |
| [Agents Island](https://github.com/garvinwong/agents-island) bridge & hooks | MIT | Separate project; required at runtime for the session list and approvals (not vendored) |

All other code and artwork in this repository — including the stroke icon set in `android/tools/icons/` (drawn for this project, following common icon naming) and the robot mascot — is original work licensed under `LICENSE.md`.
