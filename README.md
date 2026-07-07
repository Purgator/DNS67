# DNS67

A self-contained, **no-root** Android ad blocker — the name tips its hat to
[DNS66](https://github.com/julian-klode/dns66), whose DNS-filtering approach it follows,
plus one (six seveeen 🙌). It registers a local VPN whose only job
is to filter DNS: queries for known advertising/tracking domains are answered locally with
`0.0.0.0`, everything else is forwarded untouched to a real DNS resolver. No traffic other
than DNS ever enters the tunnel, so there is no speed or battery penalty on your actual
browsing traffic, and nothing leaves your device.

## Features

- **One-tap start/stop** — big toggle button, live counters (queries seen / blocked).
- **Works everywhere** — blocks ads in browsers *and* inside apps, on Wi-Fi and mobile data.
- **Auto start** — resumes after reboot and after app updates (enabled by default).
  For bulletproof persistence you can also enable *Always-on VPN* for the app in
  Android Settings → Network & internet → VPN → ⚙ → Always-on VPN.
- **Automatic configuration** — ships with the [StevenBlack hosts list](https://github.com/StevenBlack/hosts)
  (~78,000 domains) bundled in the APK, refreshes it weekly in the background.
- **Manual configuration** — upstream DNS servers (primary + fallback), blocklist URL,
  extra blocked domains, allowlist (always wins), auto-update and auto-start toggles.
- Subdomain matching: blocking `doubleclick.net` also blocks `stats.doubleclick.net`.

## 📥 Download & install (2 minutes, no technical skills needed)

1. **On your Android phone**, open this link:
   **[⬇ Download the latest DNS67 APK](https://github.com/Purgator/DNS67/releases/latest/download/DNS67.apk)**
2. When the download finishes, tap the file (or open it from your notifications).
3. Android may ask you to *allow installing apps from this source* — allow it, then
   come back and tap the file again.
4. If Google Play Protect shows a warning, tap **More details → Install anyway**
   (the warning appears for every app installed outside the Play Store).
5. Open **DNS67**, tap **Start blocking**, and accept the *Connection request* popup. Done —
   ads are now blocked in all apps and browsers, on Wi-Fi and mobile data.

Requires Android 8.0 (2017) or newer. To stop blocking at any time, open the app and tap
**Stop blocking**.

All releases with changelogs: [Releases page](https://github.com/Purgator/DNS67/releases).

## Development setup

A standard Gradle/Kotlin Android project: AGP 8.5.2, Gradle 8.7 (wrapper included),
Kotlin 1.9, compile/target SDK 34, min SDK 26. No exotic dependencies — appcompat,
material, preference-ktx, JUnit.

### 1. Prerequisites

- **JDK 17** (`java -version` should say 17.x)
- **Android SDK** — either of:
  - *Android Studio* (easiest): install it, open the repo folder, let it sync. It manages
    the SDK and writes `local.properties` for you. Skip to step 4.
  - *Command line only*: download the [command-line tools](https://developer.android.com/studio#command-line-tools-only),
    then install the needed packages:
    ```
    sdkmanager --sdk_root=<sdk-dir> "platform-tools" "platforms;android-34" "build-tools;34.0.0"
    ```

### 2. Clone and point Gradle at the SDK

```
git clone <repo-url> DNS67
cd DNS67
```

Create `local.properties` at the repo root (gitignored, machine-specific):

```properties
sdk.dir=C:/path/to/android-sdk
```

### 3. Build and test

```
gradlew.bat assembleDebug          # debug APK -> app/build/outputs/apk/debug/
gradlew.bat testDebugUnitTest      # unit tests (packet crafting, DNS, blocklist)
gradlew.bat assembleRelease        # signed release APK (needs the keystore, see below)
```

Always run the unit tests after touching `PacketCraft`, `PacketProcessor` or
`BlocklistManager` — they verify checksums and DNS wire format against an independent
reference implementation, which is the code most likely to break subtly.

### 4. Release signing (optional, needed for `assembleRelease`)

Release builds are signed only when `keystore.properties` exists at the repo root.
Both it and `release.keystore` are **gitignored — never commit them**. A base64 backup
of the keystore and its passwords lives in Bitwarden (secure note
"DNS67 release.keystore"). To restore on a new machine, copy the base64 text
to the clipboard and run in PowerShell:

```powershell
[IO.File]::WriteAllBytes("release.keystore", [Convert]::FromBase64String((Get-Clipboard -Raw).Trim()))
```

then recreate `keystore.properties`:

```properties
storeFile=../release.keystore
storePassword=<in Bitwarden>
keyAlias=adblocker
keyPassword=<in Bitwarden>
```

Keep signing with this key: Android only installs updates whose signature matches the
installed app. Debug builds use the auto-generated debug key and cannot update a
release install (uninstall first, or use debug builds throughout while developing).

### 5. Deploy to a phone

With USB debugging enabled on the device:

```
adb install -r app/build/outputs/apk/release/app-release.apk
adb logcat -s AdBlockVpnService PacketProcessor BlocklistManager AdBlockerMain
```

The second command tails the app's own log tags — start there when debugging.

### Code map

| Path | Role |
|---|---|
| `vpn/AdBlockVpnService.kt` | Foreground service: VPN lifecycle, tun read loop, retry/backoff |
| `vpn/PacketProcessor.kt` | Per-packet flow: block or forward, upstream fallback |
| `vpn/PacketCraft.kt` | Pure byte-level IP/UDP/TCP/DNS crafting — fully unit-tested, no Android deps |
| `core/BlocklistManager.kt` | Hosts parsing, suffix matching, download/refresh |
| `core/Prefs.kt` | Typed SharedPreferences access, all defaults |
| `core/BootReceiver.kt` | Auto-start on boot / app update |
| `MainActivity.kt` | UI + the permission/consent flow (order matters — see comments) |
| `SettingsActivity.kt` | Preference screen (`res/xml/preferences.xml`) |

Gotchas learned the hard way (do not regress):
- The VPN service **must stay `android:exported="true"`** — the system consent dialog
  silently refuses otherwise.
- Never launch two system dialogs at once (permission + VPN consent): sequence them,
  or the consent auto-cancels.
- Keep `applicationId` (`fr.arichard.adblocker`) unchanged — changing it makes a new app.
- Bump `versionCode` on every build you intend to install on a device.

## How it works

1. `AdBlockVpnService` establishes a VPN with a virtual interface (`10.111.222.1/24`)
   whose **only route is the virtual DNS server** `10.111.222.2/32`, which is also
   pushed to the system as the DNS server. All apps therefore send their DNS queries
   into the tunnel — and nothing else.
2. `PacketProcessor` parses each IPv4/UDP packet, extracts the DNS query name and checks
   it against the in-memory blocklist (`BlocklistManager`).
   - **Blocked** → a synthetic DNS response (`A 0.0.0.0` / `AAAA ::`) is written straight
     back to the tunnel. The app that asked gets an unroutable address and the ad never loads.
   - **Allowed** → the query is forwarded to your configured upstream resolver
     (default Cloudflare `1.1.1.1`, fallback Google `8.8.8.8`) through a socket that is
     *protected* from the VPN, and the answer is relayed back.
   - DNS-over-TCP attempts receive a TCP RST so clients fail fast instead of hanging.
3. `BootReceiver` restarts the service after boot/app updates when auto-start is enabled
   and the VPN was running.

## Limitations (inherent to DNS blocking)

- If **Private DNS** (Settings → Network → Private DNS) is set to a specific host, Android
  encrypts DNS and bypasses the filter — set it to "Off" or "Automatic".
- Apps that use their own hard-coded DNS-over-HTTPS (some browsers) bypass any DNS filter;
  disable "secure DNS" in the browser or leave it to "use system default".
- Ads served from the same domain as content (e.g. YouTube ads) cannot be blocked by DNS.
- Only one VPN can run at a time on Android; starting another VPN stops this one.
