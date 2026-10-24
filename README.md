# Calm Zone: noise cancelling Android app

Plays "anti-noise" through the phone speaker to quiet steady, humming noise around the phone's microphone.
Pure Java, no third-party libraries. Android 8.0+ (API 26).

## Get the APK (pick one)

### A. GitHub Actions (no Android Studio needed)
1. Create a free account at github.com, make a new repository, upload everything in this folder
   (including the hidden `.github` folder).
2. Open the **Actions** tab, then **Build APK**, then **Run workflow** (it also runs on every push).
3. When it finishes, open the run and download the **CalmZone-apk** artifact. It contains `app-debug.apk`.
4. Copy the APK to your phone and install it (allow "install unknown apps" when asked).

### B. Android Studio
Open this folder (File, Open), wait for Gradle sync, then **Build, Build APK(s)**.
The file ends up in `app/build/outputs/apk/debug/app-debug.apk`.

(DartPad cannot build APKs: it only runs Dart/Flutter in the browser and has no microphone access.)

## Using it
1. Put the phone near you, speaker and mic uncovered, media volume around 60%.
2. Tap **Calibrate** once in a quiet moment (it plays 1.5 s of hiss and measures the speaker-to-mic path).
3. Switch **Noise cancelling** on. It analyses the noise for 1 s, then cancels. The big number is the
   measured reduction at the phone's mic.

## How it works
`AncCore.java` is single-mic predictive feedback ANC. It subtracts its own anti-noise from the mic signal
to recover the ambient noise, learns to predict that noise one speaker-to-mic delay ahead (NLMS filter),
and plays the inverse. `tools/SimTest.java` simulates it offline:

| noise | reduction at mic |
|---|---|
| hum 120/240/360 Hz | 12.8 dB |
| fan whine 500/1000 Hz | 17.0 dB |
| hum, calibration 15% off | 6.9 dB |
| hum + strong hiss | 1.7 dB |
| white noise | 0 dB |

## Honest limits
* Works on steady/tonal noise (fans, AC, engine drone, appliance hum). Not on voices, traffic or sudden sounds.
* Only the area around the phone's mic gets quieter. Phone speakers are weak below about 200 Hz.
* Real ANC headphones use microphones at the ear and dedicated low-latency chips; a phone app cannot match them.
* Not tested on a real device. Only the DSP core was simulated. Keep volume modest; the output is
  capped, and the app re-analyses if the noise suddenly gets louder.
