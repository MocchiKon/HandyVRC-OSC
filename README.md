# HandyVRC-OSC
Sync your Handy with VRChat via OSC. Everything is set up in the app window, no config file editing needed.
<br/>
If something does not work or you want to give me feedback please open an issue.

Requirements:
* Handy updated to Firmware 4 (https://intercom.help/ohdoki/en/articles/9457625-how-to-update-from-firmware-3-to-firmware-4)
* Avatar with SPS
* Enabled OSC in VRChat
* Bluetooth on your PC (the Handy is connected over Bluetooth)
* Java 21

Mirror: https://gitlab.com/voh/HandyVRC-OSC

## Setup
Run the app by double-clicking the "start.bat" file and press **Start**: the app connects to your Handy over
Bluetooth and starts following your avatar. The window shows the current penetration value (100 = top of the stroke,
0 = bottom) and all the settings, each of them explained in a tooltip when you hover over it. Settings are saved
automatically (to `app.properties` next to the app); while the app is running, **Apply changes** restarts it with
the changed settings without reconnecting the device. Details are printed to the console and the log files.

The **Basic** tab holds what most people need: the SPS type (Penetrator/Orifice), the avatar parameter (the default
for the SPS type unless you check "Use a custom avatar parameter"), the mode, the stroke range, "Fully penetrated
at" (default 85%) and the VRChat connection (OSCQuery and the OSC port). The **Advanced** tab only shows the fine
tuning options of the selected mode.

The mode selects how the movement is sent to the device (both use HDSP, the Handy Direct Streaming Protocol):
* **Direct** streams every position to the device as it arrives (1:1), filtered by "Minimal value change".
* **Smoothed** (default) does not forward the points 1:1. Instead it
  measures how fast and in which direction you move (over the raw OSC values, including the values that are too
  small to become a point) and moves the slider towards the top or the bottom of the stroke at that speed:
  * The speed is corrected only when the measured speed changes by more than the speed change threshold,
    when you reverse direction, or when the slider fell behind by more than the divergence threshold, so
    a constant movement needs no commands at all and never turns into a stream of corrections.
  * The slider is never told to move further than you actually moved, so it cannot overshoot the next point and
    bounce back when you slow down or reverse direction.
  * When you stop moving, the slider is stopped at the position you stopped at instead of finishing the move it
    was on, and it continues from there when you move again. A slider that got slightly past your position is
    stopped where it is rather than being sent back.
  * A change of direction only counts once you moved back by at least the reversal distance (default 2% of the
    stroke), so the jitter of a slow movement cannot make the slider reverse.
  * A sudden stop of a fast movement is noticed from the next value (or when the next value is overdue). The slider
    can end up a little past you (about one frame of movement plus the Bluetooth delay); it is stopped there instead
    of being sent back, so there is no bounce.
  * VRChat only sends a value once it changed, so a slow movement arrives as a few values per second. The speed is
    measured over at least a few values, and only a gap that is clearly longer than the recent ones counts as a
    stop. Slow movements (below 25%/s) are measured over the last 500ms instead of `hdspSpeedMeasureWindowMs`, which
    keeps the jitter of a tracked hand from stopping and restarting the slider, and after a stop the slider only
    follows again once you moved 2% away from where you stopped.
  * A Handy does not move slower than about 30 mm/s, whatever it is told (`hdspMinSliderSpeed`, default 30). A
    movement slower than that is followed in steps: the slider waits until you are a little ahead and then moves to
    you at its minimum speed, stopping there, so it never runs ahead of you and never has to be pulled back.
  * A stop is sent as soon as it is noticed (it does not wait for `sendMessageEveryMs`, which only spaces out the
    speed corrections). At startup the slider position is read from the device, and a slider that is far from you
    (further than `hdspDivergenceThresholdPercent`) is brought to you at a moderate speed.
  * `hdspLogDevicePosition=true` logs the position the device reports next to the position the app believes the
    slider is at, which shows whether the device moves the way it is told to, and every received value is logged at
    TRACE level, so that a session can be replayed from its log.
  * Movements slower than 4%/s are treated as a stop. `hdspSpeedMeasureWindowMs` sets how quickly a speed change
    is followed. With `logLevel=DEBUG` the measured input rate and every command (with the speed it was planned
    with) are logged, which is what the thresholds can be tuned with.

## OSCQuery
By default ("Use OSCQuery" turned on) the app announces itself to VRChat over OSCQuery (mDNS + a small local HTTP server)
and lets VRChat send OSC messages to a free port it picks on startup. Because VRChat is told where to send messages,
the app keeps working when another application already listens on the configured OSC port (port collision),
and several OSC apps can run at the same time.
* Requires OSC enabled in VRChat and VRChat 2023.3.1 or newer.
* Only on the first start after the change: if VRChat does not send anything, restart VRChat (it caches OSC
  destinations in `%USERPROFILE%\AppData\LocalLow\VRChat\VRChat\OSC\`; deleting that folder or using the
  "Reset Config" option in the radial menu clears the cache).
* Turn "Use OSCQuery" off to go back to plain port listening on the OSC port (needed only for old VRChat
  versions or when mDNS is blocked in your network).
* OSCQuery does not change how messages are received or processed - the OSC listener and the processing/sending
  loop stay exactly the same, so the parameter update rate is unaffected. The OSCQuery HTTP server, the mDNS
  announcements and the parameter scan below all run on separate background threads.

On startup the app also reads the avatar parameters VRChat exposes through OSCQuery and logs all penetrator/orifice
candidates it finds (for example all `PenOthersNewRoot`/`PenOthersNewTip` pairs and all `PenOthers` parameters),
together with a check whether the configured avatar parameter (and the tip parameter derived from it) match one of them. This
is only a validation aid - look for `SPS parameter candidates reported by VRChat OSCQuery` in the console/log file
when the app does not react to your avatar.

On startup the app also checks in the background whether a newer version was released on
[GitHub](https://github.com/MocchiKon/HandyVRC-OSC/releases) and shows a notification when there is one.
The check is best effort - no internet connection, GitHub being unavailable or the repository being gone never blocks
or breaks the app - and it can be turned off with "Check for updates" on the Advanced tab.

<b>IMPORTANT INFORMATION:</b>
1. Reducing the stroke range too much can make slow movements too slow and the Handy may have trouble moving to the
   requested position within the requested time (the full range is recommended).
   "Fully penetrated at" is different from the stroke range: it only maps the received penetration value, so with
   50% a penetration of 50% moves the slider as far as 100% would (10% behaves like 20%, and 80% or more behaves
   like 100%). Set it to 100% to use the penetration value as it is.
2. Avatar positioning during 'act' is very important due to how SPS works. Handy stability, range and movement speed matters, if it's too low
   Handy may not be able to perform small movements in satisfying way. Therefore, handjobs work the best.
   <br/>NOTE: (Orifice SPS type only) Penetrator length is auto-detected from the SPS root/tip proximity parameters, so a custom avatar parameter has to be a `NewRoot` parameter (the `NewTip` one is derived from it).

## TODO
Hopefully I will have time and will to implement these one day (probably not as long as I don't need these in my use-case):
- [x] Auto update checking
- [x] OSCQuery (no more port collisions with other OSC apps)
- [ ] Penetration multiplier
- [x] Penetrator length auto-detection
- [ ] Default delay calculation
- [x] Bluetooth
