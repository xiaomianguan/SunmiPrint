# SunmiPrint — print anything on the P1's built-in printer

A tiny Android app that prints **arbitrary text, images, QR codes and barcodes** on the
Sunmi P1's built-in 58 mm thermal head (384 dots wide), plus a test pattern.

## Why it is built the way it is

The printer is driven through the exported system service `woyou.aidlservice.jiuiv5`
(`.WoyouService`, `android:exported="true"`, **no permission required** — verified with
`aapt dump xmltree` on the device's own `SunmiPrinterService_P1_3.1.6_release.apk`).

Binder transaction codes are assigned by **AIDL declaration order**, and Sunmi reordered
that interface between firmware revisions. This P1 runs service **3.1.6** whose interface
has 32 methods including `updateFirmware()`, while current Sunmi SDK AIDL has
`postPrintData()` instead and extra methods (`printBitmapCustom`, `getForcedDouble`, ...).
Compiling against a downloaded AIDL therefore calls the *wrong* methods — the failure mode
reported in the community as `SecurityException: Binder invocation to an incorrect interface`.

So this app does **not** use any downloaded AIDL. Instead it loads the interface class at
runtime from the device's own service APK:

```java
DexClassLoader dcl = new DexClassLoader(SVC_APK, null, null, getClassLoader());
stubCls = dcl.loadClass("woyou.aidlservice.jiuiv5.IWoyouService$Stub");
svcIface = dcl.loadClass("woyou.aidlservice.jiuiv5.IWoyouService");
cbIface  = dcl.loadClass("woyou.aidlservice.jiuiv5.ICallback");
```

then binds and calls methods by name through reflection, with an `ICallback` supplied as a
`java.lang.reflect.Proxy` whose `asBinder()` returns a throw-away `Binder` that swallows
callbacks. This makes the app immune to AIDL version drift, and any method missing on a
given firmware degrades to `n/a` instead of calling the wrong thing.

The APK holding that class is found at runtime too — `getApplicationInfo(pkg)` →
`resolveService()` → a depth-limited scan of `/system/app`, `/system/priv-app`, `/data/app`.
Firmware renames (`SunmiPrinterService_P1_<version>_release`) or an update that moves the
service to `/data/app` therefore cannot break it, and no path is compiled in.

Verified on this device: `svc 3.1.6 | fw 1.24 | model POS-P1 | state 1`.

## Build

```sh
bash build.sh                 # -> build/SunmiPrint.apk
KS=/path/to/platform.jks KS_ALIAS=platform bash build.sh   # optional custom key
```

Nothing is hardcoded — `build.sh` locates the toolchain itself and prints what it picked:

```
SunmiPrint build - autodetecting toolchain
  JDK          /home/you/android-studio/jbr  (javac 17.0.10)
  build-tools  /home/you/Android/Sdk/build-tools/36.0.0
  android.jar  /home/you/Android/Sdk/platforms/android-34/android.jar
  javac flags  -source 1.7 -target 1.7 -bootclasspath .../android.jar
  zip          zip -q
```

* **JDK** — `$JDK_HOME`, else `javac` on `PATH`, else Android Studio's bundled `jbr`
  (`~/android-studio/jbr`, `/opt/android-studio/jbr`, the macOS app bundle, …), else
  `$JAVA_HOME`. Any JDK from 8 upward works: the script probes which `javac` flags that
  generation accepts (`-source 1.7 -bootclasspath` on 8, `-source 8 -classpath` on newer)
  and reports the choice.
* **SDK** — `$SDK_ROOT` / `$ANDROID_HOME` / `$ANDROID_SDK_ROOT`, else the usual locations
  (`~/Android/Sdk`, `~/Library/Android/sdk`, `/opt/android-sdk`, …). It picks the
  **newest** build-tools that has all four tools and the **newest**
  `platforms/android-*/android.jar`.
* **Overrides** — `JDK_HOME`, `SDK_ROOT`, `BUILD_TOOLS`, `ANDROID_JAR`, plus `KS`,
  `KS_ALIAS`, `KS_PASS` for signing. If no keystore exists, one is generated at
  `keystore/ks.jks` (git-ignored).
* Requirements: a JDK with `javac` + `keytool` (a bare JRE is not enough), Android SDK
  build-tools plus one platform, and `zip` — which falls back to the JDK's `jar` if absent.
  Missing pieces produce an error naming the `sdkmanager` package to install.

Layout:

    SunmiPrint/
      AndroidManifest.xml
      build.sh
      keystore/ks.jks                            (git-ignored signing key)
      src/cc/wuersan008/smprint/MainActivity.java

## Install (important — the Sunmi install gate)

Plain `adb install` fails with:

```
Failure [INSTALL_FAILED_FORBID_BY_SERVER]
Failure [-202: Failed parse during installPackageLI: Forbid By Sunmi Server]
```

That is a Sunmi patch inside `com.android.server.pm.SunmiUtils` (strings `ALLOW_INSTALL`,
`Forbid By Sunmi Server`, `Forbid Install Android App` live in `services.odex`). The gate is
tied to the global setting **`p1_security_trans_mode`**: with it at `0` installs go through,
at `1` they are refused. So:

```sh
adb shell settings put global p1_security_trans_mode 0
adb install -r build/SunmiPrint.apk
adb shell settings put global p1_security_trans_mode 1
adb shell pm grant cc.wuersan008.smprint android.permission.READ_EXTERNAL_STORAGE
```

Note `Settings.Global ALLOW_INSTALL=1` is *not* the switch that matters here.
Uninstalling is not gated: `adb uninstall cc.wuersan008.smprint`.
Setting the flag to `0` makes SystemUI's `SecurityModeReceiver` show a tamper overlay; it
disappears again when restored to `1`.

## Usage

UI: text box + `Print text`, `Feed`, `QR`, `Barcode`, `Pick image`, `Image path`,
`Test pattern`, `Info`, `Init`. `center` aligns output; `dither images` toggles
Floyd–Steinberg dithering (on by default — the head only prints black or nothing).

Scriptable via intents (no UI needed):

```sh
adb shell am start -n cc.wuersan008.smprint/.MainActivity -e text "hello world"
adb shell am start -n cc.wuersan008.smprint/.MainActivity -e path /sdcard/pic.png
```

## Image rules learned

* Scale to exactly **384 px** wide; `Bitmap.Config.ARGB_8888`.
* The head prints **black only** — everything else is blank paper. Dither (or threshold)
  to pure black/white before sending.
* Feed in chunks (`CHUNK_H = 255` rows) — tall bitmaps in one `printBitmap()` call fail.
* Avoid `printerInit()` unless needed; some firmwares crash the binding.

## Verified

```
I SunmiPrint: printer service APK: /system/app/SunmiPrinterService_P1_3.1.6_release/SunmiPrinterService_P1_3.1.6_release.apk
I SunmiPrint: interface loaded from device APK
I SunmiPrint: connected to printer service
I SunmiPrint: svc 3.1.6 | fw 1.24 | model POS-P1 | sn 32383448183635304631D96A | state 1
I SunmiPrint: printed 36 chars
I SunmiPrint: printed image 384x192 dithered
```
