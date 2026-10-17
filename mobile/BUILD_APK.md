# Building and installing the APK

## Build

Use JDK 17 (set `JAVA_HOME` first).

```powershell
$env:JAVA_HOME = "C:\Path\To\jdk-17"
cd mobile
./gradlew assembleRelease copyReleaseApk
```

- Gradle output: `mobile/app/build/outputs/apk/release/app-release.apk`
- `copyReleaseApk` copies it to: `mobile/release/app-release.apk`
- The release build is signed with the **debug keystore** (fine for demos and sideloading; not for a store).

## Install

```
adb devices
adb install -r mobile/release/app-release.apk
```

Or copy the APK to the phone and open it (allow "install unknown apps").

## Point the app at the server

1. Run the backend on the laptop (port 8080).
2. Phone and laptop must be on the **same Wi-Fi** network.
3. Find the laptop LAN IP (`ipconfig`, IPv4 address).
4. In the app: Settings > Server address > `http://<ip>:8080` > Save address > Test connection.
5. Windows firewall: allow inbound TCP 8080, e.g. (admin PowerShell)
   `New-NetFirewallRule -DisplayName "League 8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow`
6. Android emulator: use `http://10.0.2.2:8080` (the emulator's alias for the host machine).

## Demo mode on/off

Settings > "Use demo data". On = sample data, no server needed (Settings shows "Demo data (no server)"). Off = live server (shows "Live server"). Demo accounts are listed on the Login screen.

## Troubleshooting

- `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: a build signed with a different key is installed. `adb uninstall com.nascon.league`, then install again (saved data on the device is lost).
- Test connection fails: check same Wi-Fi, the IP, the firewall rule, and that the server is running. Guest Wi-Fi often blocks device-to-device traffic.
- Plain `http://` is allowed by `res/xml/network_security_config.xml` (cleartext permitted), so no HTTPS is needed.
- `JAVA_HOME` errors: Gradle needs JDK 17, not a newer or older JDK.
