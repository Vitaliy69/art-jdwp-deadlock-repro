# ART / JDWP + GC deadlock — minimal reproducer

[![Platform](https://img.shields.io/badge/platform-Android%2016%20(API%2036)-3DDC84)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF)](https://kotlinlang.org)
[![Issue Tracker](https://img.shields.io/badge/Issue%20Tracker-%23530992434-red)](https://issuetracker.google.com/issues/530992434)

Self-contained synthetic app that reproduces the whole-VM deadlock between ART's
garbage collector and the native debug stack (`libjdwp` / `libopenjdkjvmti`) when a
JDWP debugger attaches to a *large* debuggable app on **Android 16**.

Reported as **[Google Issue Tracker #530992434](https://issuetracker.google.com/issues/530992434)**.

> This project contains **no proprietary code** — it's pure synthetic pressure — so a
> full `adb bugreport` captured from it is safe to share with Google unredacted.

## TL;DR

```bash
./gradlew :app:installDebug          # build & install (20,000 classes by default)
adb logcat -s ArtDeadlockRepro       # watch the storm
# then attach a debugger while the storm runs → UI heartbeat freezes → ANR
```

## The bug

Under a debugger, a GC lands inside the native debug stack while the JDWP event
monitor is saturated, producing an ABBA lock-ordering deadlock:

```
AllowNewSystemWeaks
  → ObjectTagTable
    → commonRef_handleFreedObject
      → debugMonitorEnter        # blocks here, holding a GC-side lock
```

Result: safepoint timeout → white screen → ANR → process killed.

## What it does

On startup (`ReproApp.onCreate`) it overlaps the three signals that open the
lock-ordering window during debugger attach:

1. **`CLASS_PREPARE` storm** — force-loads ~20,000 generated classes (`gen.Gen0…`).
2. **`THREAD_END` storm** — churns hundreds of short-lived threads for ~20s.
3. **Allocation pressure** — continuous garbage to keep the GC running.

## Requirements

- Android Studio (recent) or a local Gradle. The Gradle wrapper is committed, so
  `./gradlew` works out of the box.
- **Android 16 (API 36)** target: a physical device **or** an emulator system image.
  - To confirm it's **not vendor-specific**, use a **Google APIs / AOSP emulator
    image (API 36)** — that runs upstream mainline ART (Pixel-equivalent).
- Baseline versions in `build.gradle.kts`: **AGP 8.13.0 / Kotlin 2.1.0 / Gradle 8.14.3**.
  If sync complains about `compileSdk = 36`, bump AGP/Kotlin/Gradle to the latest —
  the repro doesn't depend on exact versions.

## Build & install

```bash
# default: 20,000 classes
./gradlew :app:installDebug

# scale the class count up if it doesn't reproduce on your device
./gradlew :app:installDebug -PreproClassCount=40000
```

### Tunable properties

| Property | Default | Purpose |
|---|---|---|
| `reproClassCount` | `20000` | Number of generated classes for the `CLASS_PREPARE` storm. Raise (40000–80000) if it doesn't reproduce. |

## Reproduce

The UI draws first (you'll see the label), then a ~25s background storm runs.
Either flow triggers the deadlock:

- **Debug button** — Studio launches with "wait for debugger" attached; the label
  appears, then the process wedges within seconds once the storm starts.
- **Run, then Attach** — launch normally; while the label still says the storm is
  running, do
  `Run → Attach Debugger to Android Process → com.example.artdeadlockrepro`.

### Expected behavior

| | Plain `Run` (no debugger) | Under the debugger |
|---|---|---|
| UI heartbeat | keeps ticking for ~25s | **freezes** |
| +/- buttons | respond | stop responding |
| Outcome | `storm done` in logcat, app stays responsive | hang → ANR → process killed |
| Native dump | — | `AllowNewSystemWeaks → commonRef_handleFreedObject → debugMonitorEnter` |

The frozen heartbeat is the visible "deadlocked" signal.

If it doesn't reproduce, raise `-PreproClassCount` (e.g. 40000–80000) and/or attach
earlier in the 25s window.

Follow progress in logcat:

```bash
adb logcat -s ArtDeadlockRepro
# "storm start: 20000 classes, 8 loaders"  -> (under debugger it wedges around here)
# "storm: all classes loaded"
# "storm done"                             -> clean run finished
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

- This is a standalone Gradle build (its own `settings.gradle.kts`); it is **not**
  part of any surrounding project.
- `debuggable=true` is set on the `debug` build type (default).
- Everything here is synthetic and license-free to publish.
```
