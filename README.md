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

## Install

Grab `app/build/outputs/apk/debug/app-debug.apk`, copy it to the phone and install it
(you may need to allow "install unknown apps" for your file manager). Open the app,
tap **Start blocking**, accept the VPN dialog once — done.

Requires Android 8.0 (API 26) or newer.

## Build from source

The repo is a standard Gradle/Kotlin Android project (AGP 8.5, compile/target SDK 34).

```
gradlew.bat assembleDebug        # or open the folder in Android Studio
```

`local.properties` must point to an Android SDK (`sdk.dir=...`). The `.tools/` folder,
if present, contains a throwaway local Gradle + SDK used for CI-style command-line builds.

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
