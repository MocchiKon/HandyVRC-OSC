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
Then run the app by double-clicking "start.bat" file. GUI only displays current penetration amount, all other information is printed to the console and log files.

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
   <br/>TIP: (ORIFICE `spsType` only) If you feel like movements are too small and movements are performed correctly (towards base of penetrator) then you
   can try setting `penetratorLength` to lower value than actual length. This will make small movements feel bigger. (Currently not possible in PENETRATOR mode)

## TODO
Hopefully I will have time and will to implement these one day (probably not as long as I don't need these in my use-case):
- [ ] Auto update checking
- [ ] Penetration multiplier (for better blowjobs in PENETRATOR mode)
- [ ] Penetrator length auto-detection
- [ ] Default delay calculation
- [ ] Maybe some improvements like: Bluetooth, improving edge case handling in penetration calculation logic
