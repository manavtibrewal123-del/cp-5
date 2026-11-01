# HMC CCTV (Howrah Municipal Corporation camera viewer)

View CP Plus (Dahua-based) cameras / DVR channels over RTSP with ID + password.

## What's in this build
- Splash screen using the official HMC seal and a festival banner (see note
  below about swapping that banner out)
- Named accounts with two roles:
  - **Admin** — can add, rename, delete cameras, and use PTZ
  - **Viewer** — can only watch cameras and save snapshots
  - The very first launch asks you to create the first admin account.
    Add more accounts (admin or viewer) from Settings afterward.
  - The app always asks to sign in again after being closed/restarted —
    there's no "stay logged in."
- Cameras grouped by location/ward, with search
- Rename, favorite, delete (long-press a camera)
- Pinch-to-zoom, snapshot, PTZ pad for ONVIF-capable cameras
- Auto-reconnect if a stream drops
- Signed release build, in addition to the usual debug build

## Swapping in the real HMC logo
Replace these files with the real artwork (keep the same file names):
- `app/src/main/res/drawable/ic_launcher_foreground.xml` - app icon
- `app/src/main/res/drawable/ic_launcher_flat.xml` - fallback icon for older phones
- The splash screen (`activity_splash.xml`) also references the foreground
  drawable; point it at a new image if you'd rather not use a vector.
The simplest approach: ask whoever designed it for a PNG, drop it in as
`app/src/main/res/drawable/hmc_logo.png`, and change the `src=` lines
above (and in `activity_splash.xml`) to `@drawable/hmc_logo`.

## Build the APK
**Android Studio:** File > Open this folder, wait for Gradle sync, then
Build > Build APK(s). Output:
`app/build/outputs/apk/debug/app-debug.apk`

**GitHub (no install):** push this folder to a GitHub repo. Actions tab >
"Build APK" > latest run > download either:
- `HMC-CCTV-debug-apk` - installs fine, but Android will keep calling it
  an "unidentified developer" app and it can't be cleanly upgraded later.
- `HMC-CCTV-release-apk` - signed with the project's own key (see below),
  the right one to actually hand out to staff.

## About the signing key
`keystore/hmc-cctv-release.jks` is a real signing key generated for this
project, with its password in `keystore/keystore.properties`. **Keep both
files private** - anyone who has them can publish an update that Android
will accept as "the same app" on someone's phone. For a one-person test
this is fine as-is. Before handing the release APK out to many staff
phones, it's worth moving the password out of the committed file and into
a GitHub Actions secret instead - ask me and I'll set that up.

If you ever lose this keystore, there is no way to issue an update to
phones that already have the old version installed - they'd need to
uninstall and reinstall. Keep a backup of the `keystore/` folder somewhere
safe (not just in this repo).

## Using it
1. Enable RTSP on the camera/DVR (default port 554).
2. Add camera: name, location/ward, IP/DDNS, port, ID, password, channel.
3. Tap a camera for live view; "View all" shows every camera in a grid
   (2x2 or 3x3).
4. Long-press a camera in the list to rename, favorite, or delete it.
5. Settings (gear icon) to turn the PIN lock on/off.

RTSP URL used:
rtsp://ID:PASS@IP:554/cam/realmonitor?channel=1&subtype=0

## Remote viewing
Same Wi-Fi works as is. From outside, use a VPN (WireGuard/Tailscale) or
router port-forwarding 554 with DDNS. Use a strong password.

## PTZ (pan/tilt/zoom)
Only works if the physical camera supports PTZ and has ONVIF turned on.
Enable it per-camera under "Advanced options" when adding the camera. This
assumes the camera's ONVIF service answers at `/onvif/device_service` on
the given ONVIF port (default 80) - true for many Dahua-based cameras, not
guaranteed for every model.

## Firebase setup (accounts + shared camera list)
Accounts and cameras are stored in Firebase so every phone sees the same cameras and the same logins.
There is exactly **one admin** (can add/rename/delete cameras and create/remove viewer accounts).
**Viewers** can only watch and save snapshots.

One-time setup (about 10 minutes):
1. https://console.firebase.google.com > Add project.
2. Project settings > Add app > Android. Package name: `com.shyamannirman.cctv`.
   Download `google-services.json` and put it in the `app/` folder (next to `app/build.gradle.kts`).
   (Debug and release APKs use the same package name, so one file works for both.)
3. Build > Authentication > Get started > Sign-in method > enable **Email/Password**.
4. Build > Firestore Database > Create database (pick a region close to you, e.g. Mumbai `asia-south1`).
5. Firestore > Rules tab > paste the contents of `firestore.rules` > Publish.
6. Build and install the app. The very first launch asks you to create the admin account -
   do this yourself right away, because whoever does it first becomes the admin.

Notes
- Passwords must be at least 6 characters (a Firebase requirement). Usernames: 3-30 letters/numbers/`. _ -`.
- Removing a viewer cuts off their access immediately. Their sign-in record stays in
  Firebase Console > Authentication, so to reuse the same username later, delete it there too.
- Viewers' phones receive the camera IP/ID/password in order to play the stream, so treat viewer accounts
  as trusted staff, and use a dedicated low-privilege login on the DVR/cameras.
- If you used the older offline version, log in as admin > Settings > "Import cameras saved on this phone".
