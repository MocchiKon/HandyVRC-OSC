# HandyVRC-OSC

TheHandy stroker and VRChat integration via OSC and Bluetooth (or through [Intiface Central](https://intiface.com/central/)).

<img src="docs/app.png" alt="The HandyVRC-OSC window" width="420"/>

## What you need

* A **Handy** updated to [Firmware 4](https://intercom.help/ohdoki/en/articles/9457625-how-to-update-from-firmware-3-to-firmware-4)
* **Bluetooth** on your PC (the app connects to the Handy over Bluetooth), or
  [Intiface Central](https://intiface.com/central/) (see [Connecting through Intiface](#connecting-through-intiface))
* An avatar with **SPS**
* **OSC enabled** in VRChat (Action menu → Options → OSC → Enabled)

## Getting started

1. Download the newest version from the [Releases page](https://github.com/MocchiKon/HandyVRC-OSC/releases):
   * **Windows:** `HandyVRC-OSC-…-windows.zip`. Everything is included, nothing else to install.
   * **Other systems:** `HandyVRC-OSC-…-all-platforms.zip`. Needs [Java 21](https://adoptium.net/) or newer.
2. Unzip it anywhere and open **HandyVRC-OSC.exe** (all-platforms: run **start.bat** on Windows or **start.sh**
   elsewhere, or open **HandyVRC-OSC.jar**).
3. Under **SPS type**, pick whether your avatar has a **Penetrator** or an **Orifice**.
4. Under **Interact with**, pick who moves the Handy: **Others** (other players), **Self** (your own body) or
   **Both**.
5. Turn on your Handy and press **Start**. That's it, go into VRChat.

## Connecting through Intiface

By default (**Connection: Direct**, recommended) the app connects to the Handy over Bluetooth by itself. If you'd rather
let [Intiface Central](https://intiface.com/central/) handle the device, for example because you already use it
for other apps:

1. Start Intiface Central, start its server and connect your device there.
2. In the app, switch **Connection** to **Intiface**. The **Intiface address** is `ws://127.0.0.1:12345` unless you
   changed the server port in Intiface Central.
3. Press **Start**. The app uses the first device Intiface offers that can be moved to a position.

Direct is still the better choice for the Handy: it has the lowest latency, and some Smoothed mode features (reading
the slider position) only work with it.

Other strokers that Intiface supports (any device that can be moved to a position) **might work** this way as well,
but they are **not tested**.

## Tips

* Use **Direct** connection mode instead of Intiface if you can (TheHandy only).
* Use the **full stroke range** if you can. A very short range makes slow movements hard for the Handy.
* Perform movements towards the base, positioning during act is very important due to how SPS works.
* If **Smoothed** mode does not work well for you then try **Direct**.

## Something doesn't work?

* **The number doesn't move:** make sure OSC is enabled in VRChat. If it's the first time you use this version,
  restart VRChat once.
* **Still nothing:** open the **Logs** tab and look for what went wrong, or turn off **Use OSCQuery** on the
  Advanced tab.
* **The Handy doesn't connect:** check that Bluetooth is on and the Handy is on Firmware 4.
* **Intiface doesn't connect:** check that the Intiface Central server is running, that it shows your device, and
  that the **Intiface address** matches the one in Intiface Central.

If you're stuck or have feedback, please [open an issue](https://github.com/MocchiKon/HandyVRC-OSC/issues).

## Building from source

For developers. You only need **JDK 21** or newer. The Maven wrapper (`mvnw`) downloads the right Maven version
on its own. On Windows, use `mvnw.cmd` instead of `./mvnw`.

```
./mvnw package
```

Builds `target/HandyVRC-OSC-<version>.jar` and `target/HandyVRC-OSC.exe`. The `.exe` on its own needs a `jre`
folder next to it, so use the release build below to get one you can run.

### Release build

```
./mvnw package -PpackageForRelease
```

Creates the two zips for a GitHub release in `target/`, each with a `HandyVRC-OSC` folder and the default
`app.properties`:

* `HandyVRC-OSC-<version>-windows.zip`: `HandyVRC-OSC.exe` with a bundled Java runtime
  ([Temurin](https://adoptium.net/) 21). It's downloaded on the first build and cached in your Maven repository.
* `HandyVRC-OSC-<version>-all-platforms.zip`: `HandyVRC-OSC.jar` with `start.bat`/`start.sh`, needs Java 21 or
  newer installed.

Add `-DskipTests` to either command to skip the tests. The version comes from `pom.xml`. To ship a newer Java
runtime, change the `windowsJre.*` properties there.
