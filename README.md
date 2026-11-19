# Nothing Screen Time Widgets

Native **Away Time** and **Screen Time** widgets from Nothing OS, built for Android 12 and later. The app is written in Kotlin and uses Android Views, RemoteViews, and Canvas.

This is an independent project and is not affiliated with Nothing Technology.

## Features

- **Away Time:** View your breaks, set a sleep schedule, and explore weekly statistics. From NOS 5.0.
- **Screen Time:** See your daily app usage, set a daily limit, and get a visual indication when you exceed it.
- Customize individual widget instances and use Material You colors and system font weight settings.
- Manage permissions, sleep schedule, and daily limit from the app.

## Getting started

1. Install the APK and open the app.
2. Grant access to usage statistics.
3. Set your sleep schedule and daily screen time limit.
4. Add the Away Time and Screen Time widgets using the app buttons or your launcher's widget menu.

Swipe vertically on the Screen Time widget to move between its two pages.

## How statistics are calculated

**Away Time** combines intervals when the device is locked, the screen is off, or the device is powered off. It counts continuous breaks of at least 10 minutes within waking hours. Brief screen wakeups while the phone remains locked do not end a break. Each day starts at the configured wake-up time. Screen Time measures app activity from midnight, so the two figures are not a partition of waking hours.

**Screen Time** combines activity intervals for apps with a launcher icon in the current profile and excludes time when the device is locked. Overlapping app intervals are counted once.

## Build

You will need JDK 17 or later, Android SDK 36, and access to Google Maven and Maven Central.

```sh
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew assembleDebug
```
The debug APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

To build a release APK:

```sh
./gradlew assembleRelease
```

To sign a release build, configure `NOTHING_KEYSTORE`, `NOTHING_STORE_PASSWORD`, `NOTHING_KEY_ALIAS`, and `NOTHING_KEY_PASSWORD` in your environment.

## Fonts

The font files NDot 55 and NType 82 are included for this project. Copyright and other rights in these typefaces belong to their respective owners. This project is independent and is not affiliated with Nothing Technology. The font files contain their own terms, which apply independently of the project source code.

*This is a project for individual use, so I let myself use AI while creating it, but I can read, understand and change all the code. Just for your info.*
