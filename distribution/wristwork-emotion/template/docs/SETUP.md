# Setup for a new wearer

Give a coding agent this request after cloning or forking the repository:

> Set up wristwork-emotion for my Pixel Watch 5. Read AGENTS.md and docs/SETUP.md.
> Use only my private configuration and archive. Do everything possible from the
> terminal, then give me one watch-menu checklist. Preserve app data on upgrades,
> run the read-only doctor, and let me enter the verification label myself.

The wearer supplies device-menu access and decides where private data lives. The
agent should handle configuration, build, pairing commands, installation, and
read-only verification. Never reuse another wearer's endpoint, token, topics,
archive, APK, OAuth project, signing material, prompt schedule, or health data.

## 1. Choose the private data service

The app uses an authenticated ntfy endpoint with four topics: labels, Fitbit flags,
health, and prompts. An existing private ntfy deployment is fine. It must allow the
watch's bearer token to read and write those topics, retain enough message cache for
offline recovery, and be reachable from the watch. Use HTTPS for ordinary remote
use. A cleartext LAN address is suitable only on a trusted private network.

The optional `backend/compose.yaml` starts an authenticated deny-all ntfy server and
an append-only archiver. On a machine with Docker Compose:

```powershell
Set-Location backend
docker compose --env-file .env.example up -d ntfy
docker compose --env-file .env.example exec ntfy ntfy user add emotion
docker compose --env-file .env.example exec ntfy ntfy token add --label=watch emotion
Set-Location ..
```

The user command asks for a password. Save the generated token in a password manager;
do not put it in chat or Git. Configure an HTTPS reverse proxy or a private network
route before relying on the service away from home.

## 2. Generate local configuration

Use the watch-reachable base URL and the token just created:

```powershell
./setup/New-LocalConfig.ps1 -BaseUrl 'https://notify.example.org' -Token '<private-token>'
./setup/Grant-BackendAccess.ps1
Set-Location backend
docker compose up -d
Set-Location ..
```

The generator writes ignored `config.properties` and `backend/.env` files with four
new random topic names. `Grant-BackendAccess.ps1` grants the `emotion` user access to
those topics. If an existing ntfy service is used, run only the generator and grant
equivalent ACLs on that service. Review `git status --ignored` before proceeding;
neither private file should be tracked.

## 3. Build and connect the watch

Install JDK 21, Android SDK platform 36+, platform-tools, and Git. Then run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

On the watch, enable Developer options, ADB debugging, and Wireless debugging. Open
**Pair new device** and use its address/code with `adb pair`. The normal connection
address is shown one level above and usually has a different port:

```powershell
adb pair <pair-address>
adb connect <connection-address>
adb devices -l
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

Always select the exact serial. `install -r` preserves existing labels and queued
records. Never uninstall or clear app data after real collection begins.

## 4. Permissions and watch face

Launch **wristwork emotion** once from the app list. Grant notifications, activity
recognition, heart rate, background health data, and skin temperature when the watch
offers them. An authorized setup agent can request the runtime permissions through
adb when the watch UI does not surface them coherently:

```powershell
adb -s <serial> shell pm grant com.emotiveautomaton.wristworkemotion android.permission.POST_NOTIFICATIONS
adb -s <serial> shell pm grant com.emotiveautomaton.wristworkemotion android.permission.ACTIVITY_RECOGNITION
adb -s <serial> shell pm grant com.emotiveautomaton.wristworkemotion android.permission.health.READ_HEART_RATE
adb -s <serial> shell pm grant com.emotiveautomaton.wristworkemotion android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND
adb -s <serial> shell pm grant com.emotiveautomaton.wristworkemotion android.permission.health.READ_SKIN_TEMPERATURE
```

Platform/device policy may keep a permission in watch Settings; report the actual
result of each grant. Fitbit flag capture is optional and uses the separate
**Notification access** setting for the wristwork emotion flag listener.

Add the wristwork emotion complication to a watch-face slot. Add its health
complication if passive collection is wanted. The emotion complication owns prompt
scheduling, so no unrelated complication is required.

## 5. Verify without contaminating the archive

Run the doctor; it performs health and authenticated read-only topic requests and
does not publish labels:

```powershell
./setup/doctor.ps1 -Serial '<serial>'
```

The wearer then enters and confirms one genuine current state. Verify separately:

- the intended package is installed on the intended watch;
- tapping the complication opens the grid and confirmation updates the face;
- a new label envelope reaches the private archive once logically, without printing
  its emotional contents during verification;
- passive data arrives, or is recorded as disabled/unverified;
- dismissing the next naturally scheduled prompt clears `NEW` without a label;
- offline replay, remote reachability, and full-wear-day battery behavior are marked
  verified only after they are actually observed.

Keep a private setup receipt with versions, enabled capabilities, and dates. Do not
include endpoints, tokens, topic names, serials, or health values in the public repo.
