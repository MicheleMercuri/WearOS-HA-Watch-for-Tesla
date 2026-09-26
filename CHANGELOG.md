# Changelog

## 1.3 (2026-09-26): first public release

### Configuration

- All personal settings moved to `local.properties`: Home Assistant URL, car name, entity
  prefix, per-entity overrides, optional gate and light buttons. Nothing personal is left in
  the source code. Unknown keys and malformed values stop the build with a clear message.
- Buttons for gates and lights appear only if configured, each bound to its own slot.
- English interface, Italian translation. Range unit and climate limits come from Home Assistant.

### Security

- The token is no longer compiled into the APK (installed APKs are readable by other apps).
  `./gradlew provisionToken` sends it through adb's standard input to a content provider that
  accepts calls only from the adb shell or root; the app stores it encrypted with an Android
  Keystore key. No broadcast, so no copy of the token in the system's broadcast history.
- Works with a non-admin token (`ha.read_mode=states`, or `auto` which detects it once per token).
- After a 401/403 the app stops refreshing and sending commands with that token, also across
  restarts, until a new token or a manual test: every refused call counts as a failed login in
  Home Assistant and can lead to an IP ban.
- Tile buttons use LoadAction instead of exported relay activities: no other app on the watch can
  trigger a command. Each drawn tile uses new button ids, tied to a random per-install value, and
  each id runs at most once. Verified on a Galaxy Watch8: one tap, one command, none repeated by
  tile refreshes.
- With `ha.allow_http` the token is sent only on Wi-Fi.
- HTTPS only by default; plain http needs an explicit `ha.allow_http=true`.
- Safer local shortcut (`ha.lan_ip`): the app connects to the local IP but verifies the
  certificate of the public host name, so the token is never sent to another device that happens
  to use the same IP on a different Wi-Fi network. Before, the token went in clear text to that
  IP on any Wi-Fi.

### Car and gate safety

- Tile: fixed Lock and Unlock buttons. Before, one button changed meaning in place, so a tap aimed
  at Lock could unlock a car that had locked itself in the meantime.
- In the app, two-state buttons ignore taps for 1.2 s after their state changes, and safety
  buttons are labelled with the action they perform.
- Commands are never retried automatically: OkHttp retries, redirects and "503 Retry-After" re-sends
  are disabled for service calls, and the route is switched only if the request was never written.
  A command without an answer is reported as "sent, no answer", the car state is checked, and
  further commands on that entity are refused meanwhile.
- Lock and unlock are verified against states read after the command (unlock only by an explicit
  "unlocked"), with a message and a vibration that also works with the wrist down.
- Gates: taps are refused while a call is running and for 10 s after it; unavailable gates are not
  triggered.
- Climate and Sentry buttons are labelled with their action; ignored taps give a double tick.
- Optional entities can be switched off with `entity.<key>=none`.
- With placeholder data from the Tesla integration (after a restart with the car asleep), trunk,
  frunk, charge port and window commands are refused and only Lock is offered.
- +/- values: limits from the entities, never moved the wrong way, not started from made-up
  values, and sent even if you leave the screen. Commands run in the application scope.
- Commands wait up to 60 s, enough for Home Assistant to wake the car, and a short foreground
  service ("Sending to the car…" notification) keeps the app online meanwhile: Wear OS cuts the
  network of background apps about 10 s after they leave the screen (seen on the watch), which lost
  the answer of commands to a sleeping car.
- A 5xx answer to a command is treated as "may have run" (hold, check, gate pause), not as "not sent".
- Missing entities (wrong prefix) are reported instead of showing dashes.
- Light buttons send turn on / turn off according to the state shown.

### Project

- Gradle wrapper (with distribution checksum), CI build of debug and release, pre-commit hook
  that refuses tokens and personal values.

## 1.2 (2026-09-16)

- Tile with three buttons: two gates side by side, car lock below.

## 1.1 (2026-09-16)

- Single `POST /api/template` refresh (about 800 bytes instead of 1.3 MB of `/api/states`).
- Local-first networking with fallback to the public URL.
- State cached across process restarts; tile refreshes itself.
- +/- buttons send one command, 1 s after the last tap.
- Blue/orange palette readable with red-green colour blindness.
- R8 release build with baseline profiles: cold start 0.45-0.93 s (was about 2 s),
  memory 23 MB (was 100 MB), APK 3.9 MB (was 34.8 MB), measured on a Galaxy Watch8.

## 1.0 (2026-04-20)

- First version: main screen, climate, doors/trunk, charging, battery complication, tile.
