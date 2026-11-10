THE SUN TRIP 2028 – Android app (demo version 0.1)
====================================================

This folder is the complete Android project. Build it once into an APK
on a Windows PC, then install the APK on your Xiaomi phone.

What this version does
----------------------
- Shows the full app full screen: Ride, Route, Energy, Talk, Trip.
- Navigation, slope, weather, sunrise/sunset, battery, panels 1-5,
  roof tilt -30/+30, extra panel on/off, motor power -/+ , dark mode.
- "Load GPX" opens your phone's files to pick the komoot GPX.
- "Run simulation" simulates the whole trip.
- Music plays songs saved on the phone ("Add songs from phone").
- The screen stays on while the app is open.

Still SIMULATED in this version (next version):
- GPS position and speed, Bluetooth data from the bike,
  the real walkie-talkie, QQ Music controls, spoken directions
  with the screen off.


BUILD THE APK (Windows, about 20 minutes the first time)
--------------------------------------------------------
1. Install Android Studio (free): https://developer.android.com/studio
   In China, if that site is slow, use https://developer.android.google.cn/studio
2. Unzip this folder, for example to C:\TheSunTrip2028
3. Start Android Studio > Open > choose the TheSunTrip2028 folder.
4. Wait until the bottom bar finishes "Gradle sync" (it downloads the
   Android tools the first time; this needs internet).
   If Android Studio asks to install an SDK or update Gradle, click Yes / OK.
5. Menu: Build > Build App Bundle(s) / APK(s) > Build APK(s).
6. When it says "APK(s) generated", click "locate".
   The file is:  app\build\outputs\apk\debug\app-debug.apk

Test it on Windows first (optional): in Android Studio click the green
Play button to run it in the built-in phone emulator, or drag
app-debug.apk onto MuMu Player.


INSTALL ON THE XIAOMI (HyperOS)
-------------------------------
1. Copy app-debug.apk to the phone (USB cable, or send it to yourself
   in WeChat and save it).
2. Open it with the Files app.
3. Allow "Install unknown apps" for that app when asked.
4. HyperOS shows a security check / warning: tap Continue > Install.
5. The app appears as "The Sun Trip 2028" with the sun / trike icon.

Recommended phone settings for the trip:
- Settings > Apps > The Sun Trip 2028 > Battery saver: No restrictions
- Settings > Apps > The Sun Trip 2028 > Autostart: On


Files
-----
app/src/main/assets/index.html   the app itself (the same page as the demo)
app/src/main/java/.../MainActivity.java   opens the page full screen
app/src/main/res/mipmap-*/ic_launcher.png  app icon
play-store-icon-512.png          large icon
