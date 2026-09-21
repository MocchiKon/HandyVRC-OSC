# HandyVRC-OSC
Sync your Handy with VRChat via OSC. This is early release, so setup process isn't fully streamlined yet
but if you fill out properties correctly everything should work well.
<br/>
If something does not work or you want to give me feedback please open an issue.

Requirements:
* Handy updated to Firmware 4 (https://intercom.help/ohdoki/en/articles/9457625-how-to-update-from-firmware-3-to-firmware-4)
* Avatar with SPS
* Enabled OSC in VRChat
* Java 21

Mirror: https://gitlab.com/voh/HandyVRC-OSC

## Setup
Before using this app you need to fill few values in app.properties file
(If you don't have it then just run app and it will create one if it's missing).
Every property is explained in the properties file.<br/>
Then run the app by double-clicking "start.bat" file. GUI only displays current TheHandy position (100 = top, 0 = bottom), all other information is printed to the console and log files.

`processingAlgorithm` (in app.properties) selects how points are delivered to the device:
* `HSP` (default) buffers timestamped points on the device and works with both `API` and `BLUETOOTH` connection modes.
* `HDSP` (Handy Direct Streaming Protocol) streams the newest position directly without device-side buffering and works with `BLUETOOTH` only.
  When `hdspTiming=true` it also measures the message delay and its jitter and schedules commands so that a move in progress is not interrupted.

## OSCQuery
By default (`useOscQuery=true`) the app announces itself to VRChat over OSCQuery (mDNS + a small local HTTP server)
and lets VRChat send OSC messages to a free port it picks on startup. Because VRChat is told where to send messages,
the app keeps working when another application already listens on the OSC port from `listenOnPort` (port collision),
and several OSC apps can run at the same time.
* Requires OSC enabled in VRChat and VRChat 2023.3.1 or newer.
* Only on the first start after the change: if VRChat does not send anything, restart VRChat (it caches OSC
  destinations in `%USERPROFILE%\AppData\LocalLow\VRChat\VRChat\OSC\`; deleting that folder or using the
  "Reset Config" option in the radial menu clears the cache).
* Set `useOscQuery=false` to go back to plain port listening on `listenOnPort` (needed only for old VRChat
  versions or when mDNS is blocked in your network).
* OSCQuery does not change how messages are received or processed - the OSC listener and the processing/sending
  loop stay exactly the same, so the parameter update rate is unaffected. The OSCQuery HTTP server, the mDNS
  announcements and the parameter scan below all run on separate background threads.

On startup the app also reads the avatar parameters VRChat exposes through OSCQuery and logs all penetrator/orifice
candidates it finds (for example all `PenOthersNewRoot`/`PenOthersNewTip` pairs and all `PenOthers` parameters),
together with a check whether the configured `avatarParameter` and `penetratorTipParameter` match one of them. This
is only a validation aid - look for `SPS parameter candidates reported by VRChat OSCQuery` in the console/log file
when the app does not react to your avatar.

On startup the app also checks in the background whether a newer version was released on
[GitHub](https://github.com/MocchiKon/HandyVRC-OSC/releases) and shows a notification when there is one.
The check is best effort - no internet connection, GitHub being unavailable or the repository being gone never blocks
or breaks the app - and it can be turned off with `checkForUpdates=false` in app.properties.

<b>IMPORTANT INFORMATION:</b>
1. It's highly recommended to run this app on the remote user side as this will allow for perfect synchronization
   (by matching `pointsOffset` value to the delay between remote user action and you seeing it on your display).<br/>
   If you (as in the owner of the Handy to be controlled) want to run this app on your PC then try to set `pointsOffset`
   and `sendMessageEveryMs` as low as possible, without introducing data skipping.
2. In order to decrease latency between you and TheHandy servers set "connection mode" to "Wifi" instead of "Wifi & Bluetooth"
   (can be done here https://onboarding.handyfeeling.com/#/onboarding/settings/connection).
   The light on Handy should be only pink in the Wifi only mode. (Device Latency can be checked at https://new.handyfeeling.com/#/settings)
3. Reducing stroke range limits too much can make slow movements too slow and Handy may have trouble moving to the request position within requested time
   (recommended `sliderMin=0` and `sliderMax=1` for full range)
4. Avatar positioning during 'act' is very important due to how SPS works. Handy stability, range and movement speed matters, if it's too low
   Handy may not be able to perform small movements in satisfying way. Therefore, handjobs work the best.
   <br/>NOTE: (ORIFICE `spsType` only) Penetrator length is auto-detected from the SPS root/tip proximity parameters, so `avatarParameter` has to be a `NewRoot` parameter (the `NewTip` one is derived from it).

## TODO
Hopefully I will have time and will to implement these one day (probably not as long as I don't need these in my use-case):
- [x] Auto update checking
- [x] OSCQuery (no more port collisions with other OSC apps)
- [ ] Penetration multiplier
- [x] Penetrator length auto-detection
- [ ] Default delay calculation
- [x] Bluetooth
