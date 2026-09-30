# BestDroid

The BeSTspeech / Keynote Gold speech synthesizer as an Android
`TextToSpeechService` — an Android port of iBestSpeech — with a Google-TTS
fallback so the service never goes quiet.

Package `org.bestdroid.tts`, app name BestDroid, minSdk 26, targetSdk 34.

## Voices

All twenty engine builds, each exposed as its own voice:

| Build | Language | Reads | Rate |
|---|---|---|---|
| 1995 | en-US | Latin | 11025 Hz |
| 1998ENG/DUT/FRN/GRM/ITL/SPN | en/nl/fr/de/it/es | Latin | 11025 Hz |
| 2006ARA | ar-SA | CP1256 (also Latin) | 10000 Hz |
| 2006DUT/ENG/FRE/GER/ITA/JPN/POL/POR/SPA | nl/en/fr/de/it/ja/pl/pt/es | Latin | 10000 Hz |
| 2006GRE | el-GR | CP1253 (also Latin) | 10000 Hz |
| 2006HEB | he-IL | Latin (Hebrew phonetics in Latin letters) | 10000 Hz |
| 2006RUS | ru-RU | CP1251 (Latin falls back to English) | 10800 Hz |

Each utterance streams at its build's native rate. Where a build cannot
read the script it is given, English speaks instead.

## Text handling (ported from iBestSpeech `Shared/`)

- Tags become a space, entities decoded; `<sub>` speaks its alias.
- Emoji / non-ASCII the engine cannot read are spoken as CLDR
  descriptions (`assets/cldr/<lang>.txt`, 29,982 entries, per-character
  English fallback).
- 144-term pronunciation dictionary (case-sensitive, whole-word).
- Language-detect voice switch (script first, then function words;
  refuses far more often than it guesses; English never switches on Latin).
- Two adjacent numbers get a colon separator (a comma would end the text
  on all thirteen 2006 builds); user commas softened to colons except
  between digits; clock-time colons become hyphen+space; IPv4 dots,
  edge dots (`claude.ai`), caps tails (`UIs`), invisible/bidi chars,
  tilde→space, diacritic folding, unit plurals.

## Google fallback

Prefs (`bestdroid` SharedPreferences): enabled default ON, fallback
package default `com.google.android.tts`, proportional timeout
(15 s + 15 ms/char). Native synth runs on an executor under that timeout;
on timeout, exception, or all-silence output, the same text is synthesized
through the fallback package to a temp wav whose PCM is streamed to the
same callback. A catch-all around synthesis reports `error()` rather than
letting the service die.

## Tables / rights

Personal build: every `openbst` C source **including** `src/data/*.c` is
compiled into `libbestdroid.so` (the iOS `--no-tables` option is not used).
See `NOTICE` (also shipped in `assets/NOTICE`): do NOT redistribute this
APK publicly.

## Build

Needs the Android SDK (point `local.properties` `sdk.dir` at it) and JDK 17
(`org.gradle.java.home` or `JAVA_HOME`). Gradle 8.13, AGP 8.5.2, Kotlin 1.9.24.

```
git clone --recurse-submodules <this-repo>
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Pinned engine source: `vendor/openbst` submodule = Mudb0y/openbst @
`a151e78` (tables-in build; see NOTICE — personal use only).

Headless logic tests (no device needed) — compile the pure-Kotlin layer
with the cached Kotlin compiler and run:

```
cd tools/headless-test
# see TestMain.kt; needs kotlinc or the gradle-cached compiler jars
```

36 checks cover rate/pitch mapping, the text repairs, language detection,
and the voice catalog.
