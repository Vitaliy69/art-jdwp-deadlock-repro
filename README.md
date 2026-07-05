# ART / JDWP + GC deadlock — minimal reproducer

Self-contained synthetic app that reproduces the whole-VM deadlock between ART's
garbage collector and the native debug stack (`libjdwp` / `libopenjdkjvmti`) when a
JDWP debugger attaches to a *large* debuggable app on Android 16.

Reported as **Google Issue Tracker #530992434**.

This project contains **no proprietary code** — it's pure synthetic pressure — so a
full `adb bugreport` captured from it is safe to share with Google unredacted.

## What it does

On startup (`ReproApp.onCreate`) it overlaps the three signals that open the
lock-ordering window during debugger attach:

1. **`CLASS_PREPARE` storm** — force-loads ~20,000 generated classes (`gen.Gen0…`).
2. **`THREAD_END` storm** — churns hundreds of short-lived threads for ~20s.
3. **Allocation pressure** — continuous garbage to keep the GC running.

Under a debugger, a GC then lands inside `AllowNewSystemWeaks → ObjectTagTable →
commonRef_handleFreedObject → debugMonitorEnter` while the JDWP event monitor is
saturated → ABBA deadlock → white screen → ANR.

## Requirements

- Android Studio (recent) or a local Gradle.
- Android 16 (API 36) target: a physical device **or** an emulator system image.
  - To confirm it's **not vendor-specific**, use a **Google APIs / AOSP emulator
    image (API 36)** — that runs upstream mainline ART, i.e. Pixel-equivalent.
- Versions in `build.gradle.kts` (AGP 8.13.0 / Kotlin 2.1.0 / Gradle 8.14.3) are a
  recent baseline. If sync complains about `compileSdk = 36`, bump AGP/Kotlin/Gradle
  to the latest — the repro doesn't depend on exact versions.
- The Gradle wrapper is committed, so `./gradlew` works out of the box.

## Build & install

```bash
# default 20,000 classes
./gradlew :app:installDebug

# scale the class count up if it doesn't reproduce on your device
./gradlew :app:installDebug -PreproClassCount=40000
```

## Reproduce

The UI draws first (you'll see the label), then a ~25s background storm runs. Either
flow triggers the deadlock:

- **Debug button** — Studio launches with "wait for debugger" attached; the label
  appears, then the process wedges within seconds once the storm starts.
- **Run, then Attach** — launch normally; while the label still says the storm is
  running, do `Run → Attach Debugger to Android Process → com.example.artdeadlockrepro`.

Expected on a plain `Run` (no debugger): the label shows immediately, the **UI
heartbeat keeps ticking** and the +/- buttons respond for ~25s (`storm done` in
logcat) — it should **not** look hung.

Expected under the debugger: the heartbeat **freezes** and the buttons stop responding
→ hang → ANR → process killed. That frozen heartbeat is the visible "deadlocked"
signal. On success the native dump shows
`AllowNewSystemWeaks → commonRef_handleFreedObject → debugMonitorEnter`.

If it doesn't reproduce, raise `-PreproClassCount` (e.g. 40000–80000) and/or attach
earlier in the 25s window.

Follow progress in logcat:

```bash
adb logcat -s ArtDeadlockRepro
# "storm start: 20000 classes, 8 loaders"  ->  (under debugger it wedges around here)
# "storm: all classes loaded"
# "storm done"                             ->  clean run finished
```

## Capture the evidence (while it's hung)

A Java dump will time out (that's the bug), so capture native state:

```bash
# pid of the hung process
adb shell pgrep -f com.example.artdeadlockrepro

# Full bugreport — SAFE to share unredacted (synthetic app, no private data):
adb bugreport art-deadlock-hang.zip
#   inside dumpstate-*.txt, look for:
#     "VM TRACES JUST NOW"          -> forced native thread dump
#     "Java unwind failed for pid"  -> confirms the safepoint timeout
#     "Waiting Channels: pid <PID>" -> per-thread kernel wait states

# Or, with root/debuggerd:
adb shell debuggerd -b <PID> > native_threads.txt
```

For the issue tracker, capturing this on a **Google-image API 36 emulator** answers
both "is it reproducible on a Pixel/Nexus?" and "provide a shareable bug report" at
once, without any NDA concerns.

## Notes

- This is a standalone Gradle build (its own `settings.gradle.kts`); it is **not** part
  of any surrounding project. Move it to its own public repo before sharing the link.
- `debuggable=true` is set on the `debug` build type (default).
- Everything here is synthetic and license-free to publish.
