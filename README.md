# Banner Keyboard

An Android keyboard to allow for simpler typing using Minecraft banners.

Allows users to create and edit banners, add "words" to languages that can replace a sequence of banners while typing, and exports directly to image files or banner-writer links (hold Enter to switch).

## Install

1. Download the APK from [Releases](https://github.com/Roboardo/bannerlang_kb/releases).
2. Optional: check that it was built here from the tagged source:
   `gh attestation verify bannerlang_kb-X.Y.Z.apk -R Roboardo/bannerlang_kb`
3. Install it, then enable Banner Keyboard in Android's keyboard settings.

Works on Android 8.0 or newer.

## Usage

1. Open the app and follow "Get started" to enable the keyboard.
2. Make a language under Languages. Each language needs a banner as its icon.
3. Add banners to the language as keys or words (or import from banner-writer or bannerdb).
4. As you type, matching words will show up above the keys: tap one to replace what you typed, or tap `→` to keep the banners as they are.
5. Hit send to export the message as an image, BannerFont text or a banner-writer link (hold it to switch).

## Things to know

- Tap the language key to cycle between languages, or hold it to open the app.
- You can select one or more banners in the preview, then use the edit key to edit that word or make a new one from the selection, without leaving the keyboard.
- Paste banners as banner-writer links, BannerFont text or banner codes.
- Banners that can't be made in a loom (more than 6 patterns) get a red outline. You can turn this off in Settings.
- Your languages are backed up with Android's own backup or synced to a custom file. You can edit this in Settings → Backup.

## Building

```sh
./gradlew assembleDebug
```

Needs JDK 17 and the Android SDK. The first build downloads the Minecraft client jar to get
the banner textures.

## License

GPL-3.0, see [LICENSE](LICENSE).
