# VIVO OTA Tracker (Android)

VIVO OTA Tracker is an open-source Android app that fetches official OTA firmware download links for various VIVO / iQOO devices directly from official servers.

By loading the native `libvivoseckey.so` library, this app processes the necessary request parameters for system updates on-device, allowing you to obtain full or incremental firmware download links without needing a PC or remote server.

---

**Core Features:**
* 📦 **Firmware Fetching**: Query and extract firmware download direct links for various VIVO / iQOO models from official servers.
* 🔒 **Native Encryption**: Powered by `libvivoseckey.so` JNI engine — all encryption/decryption happens on-device, no server required.
* 📱 **Multi-Device Support**: Built-in database of 14 series and 395+ models, plus manual input mode for devices not in the database.
* 🔄 **Full / Incremental**: Query both full firmware packages and incremental OTA updates.
* 🗂️ **Payload Dumper**: View partition list from OTA payload online, with multi-select batch extraction of partition images.
* 🌐 **Multilingual**: 11 languages — English, 简体中文, 繁體中文, Русский, Bahasa Indonesia, ภาษาไทย, Tiếng Việt, हिन्दी, 日本語, 한국어. Follows system language automatically.
* 📋 **Query History**: Collapsible, locally persisted query history with one-tap link copying.
* 📝 **Inline Changelog**: Update logs parsed and displayed directly in-app, no browser needed.
* 🔒 **Security Info**: Displays security patch level and update date when available.
* 🌙 **Auto Theme**: Dark / Light theme follows system setting automatically.

---

### 🛠️ Tech Stack

* **Kotlin** + **Jetpack Compose** — Modern Android UI toolkit
* **Miuix KMP** (top.yukonga.miuix) — UI component library
* **Vivo SecKey SDK** — Native JNI encryption via `libvivoseckey.so`
* **AndroidX ViewModel** + **StateFlow** — Architecture components

---

### 📱 Screenshots

<p align="center">
  <img src="screenshots/home.jpg" width="270" />
  <img src="screenshots/manual_mode.jpg" width="270" />
  <img src="screenshots/query_result.jpg" width="270" />
</p>
<p align="center">
  <img src="screenshots/changelog.jpg" width="270" />
  <img src="screenshots/history.jpg" width="270" />
  <img src="screenshots/about.jpg" width="270" />
</p>

---

### 🔧 Build

Requirements:
* JDK 25+
* Android SDK (compileSdk 37)
* Gradle 9.6+

```bash
./gradlew :app:android:assembleDebug
```

APK output: `app/android/build/outputs/apk/debug/`

---

### 📜 Disclaimer

This project is for technical learning and communication purposes only. Do not use it for any illegal or commercial purposes. The user bears all consequences for any problems caused by improper use.

---

### 👏 Credits

| Role | Info |
|------|------|
| Developer | [mytiantian001](https://www.coolapk.com/u/4430874) |
| Reference Project | [YuKongA / Updater-KMP](https://github.com/YuKongA/Updater-KMP) |

---

### 🔗 Related Projects

* **PC Version** (PyQt5 + Fluent-Widgets): [mytiantian001/VIVO-OTA-Tracker](https://github.com/mytiantian001/VIVO-OTA-Tracker)
* **Updater-KMP** (Kotlin Multiplatform framework): [YuKongA/Updater-KMP](https://github.com/YuKongA/Updater-KMP)

---

© 2026 mytiantian001
