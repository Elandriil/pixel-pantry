Pixel Pantry server for Windows
===============================

First run
---------
1. Unzip this folder anywhere (for example C:\PixelPantry).
2. Double-click "Start Pixel Pantry.vbs" (hides the console window). Double-clicking
   PixelPantry.exe directly also works but shows a console window. Keep
   ffprobe.exe in the same folder. For autostart, put a shortcut to the .vbs on
   the desktop or in the Startup folder (Win+R, type shell:startup).
3. If Windows SmartScreen warns about an unknown publisher, choose
   "More info" -> "Run anyway".
4. When Windows Firewall asks, allow access on PRIVATE networks. This is
   needed so the TV can reach the server.
5. A tray icon appears near the clock.
6. Open http://localhost:8765/admin in a browser.
7. Set the media folder (for example D:\Media) and save.
8. TMDB API key: create a free account at https://www.themoviedb.org, go to
   Settings -> API, and paste the "API Read Access Token" or the v3 API key.
9. Optional, for subtitles: create an account at https://www.opensubtitles.com,
   create an API consumer key, then enter the key, your username and password.
10. Click "Rescan now". The library fills in as titles are matched.
11. The TV app finds the server automatically on the local network.

Files
-----
- Data (database, images, settings) lives in the "data" folder next to the exe.
- Log file: data\logs\server.log (tray menu: "Open log folder").
- To update, replace PixelPantry.exe only. Keep the "data" folder.

Deleting from the TV app
------------------------
- Deleted files go to the Recycle Bin. Network shares and removable drives
  (USB disks, SD cards) have no Recycle Bin, so deleting media stored there is
  refused ("Cannot move to Recycle Bin: ... is on a network or removable
  drive") and nothing is deleted. Delete those files in Explorer instead.

If the TV cannot find the server
--------------------------------
- The TV and this PC must be on the same Wi-Fi / LAN (not a guest network).
- Check the Windows Firewall rule for PixelPantry.exe allows PRIVATE networks
  (Windows Security -> Firewall -> Allow an app through firewall).
- Make sure the network is set to "Private" in Windows network settings.
- Try the manual address shown on the admin page (Status section).
