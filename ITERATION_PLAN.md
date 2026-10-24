# Flip5 external screen assistant iteration plan

Target device: Galaxy Z Flip5, Android 16/One UI 8.5, no root, works with Shizuku. The product is designed for daily use on the external screen. When the internal screen is unavailable, settings should still be completed on the external screen.

Current delivery: 0.6.0 (Build 7), corrected and completed page-by-page layout checking for both DPIs based on user real-machine screenshots. The limited automatic iteration of 0.3–0.5 has been completed as planned, and this version is triggered by new feedback; subsequent corrections will be based on Flip5 real machine retests. Existing sidebar tools continue to be compatible. All "local passes" and "Samsung real device passes" are recorded separately.

## 0.3.0: Status and basic entrance (implementation and local acceptance completed, Samsung real machine to be inspected)

-[x] Permanent status bar: time, Wi-Fi, battery percentage and charging status, alarm clock, notification application icon; network speed and mobile network information are displayed on demand. Missing data is not signaled and the notification text is not saved.
-[x] Dual gesture bar at the bottom: drag the notification panel inward on the left side, and drag the control center inward on the right side; the panel follows the hand frame by frame. Adapt to four directions to avoid conflicts with icon clicking and left and right page turning.
-[x] The desktop and system panel retract buttons by default, leaving only double horizontal bars; normal application recovery buttons. Long press any bar to switch temporarily, keep it within the same application, and restore the rules after changing applications. The touch window is truly reduced when retracted; foreground recognition only processes the metadata of the specified external screen window, and the settings support per-application configuration. Specific reports on the One UI home page, notification page, and card page still need to be confirmed by real devices.
-[x] The user has chosen to expand the self-drawn notification panel, and Samsung’s native notifications remain as independent entrances.
-[x] The fixed key opens the quick application center by default: only the scrollable collection bar on the left is initially displayed; edit collections, expand/collapse the application page on the right; search and scroll are available on the right; independent pinned applications at the bottom do not scroll with the list.
-[x] App startup keeps the specified external screen. Refer to vivo's sidebar interaction, and there is no commitment to obtain vivo's system window, OCR or global translation capabilities.
-[x] The settings center is grouped by tasks and supports search; permissions display the true status and functional impact; basic configurations are previewed; diagnostic information is not mixed into daily settings.
-[x] About page contains version number, build number, local changelog and licensing information.
-[x] Build, related unit tests, native simulator gesture/interface acceptance, output the same signature APK and verification records. 16 JVM tests, 86 native component assertions passed, 0 Lint errors. See `dist/dev-design/VALIDATION-0.3.0.md`.

## 0.4.0: Avoidance and operation recovery (implementation and local acceptance completed, Samsung real machine to be inspected)

-[x] Temporary avoidance: When the input method window appears on the selected external screen, a thin strip will be harvested, and it will be restored after disappearing; long press can temporarily expand it. Camera and video use are in accordance with application rules and do not claim to determine the playback/shooting status. The actual input method window report of One UI is pending acceptance on the real machine.
-[x] Gesture tolerance: flick switch, four-way title dragging and quick retracting, short swipe rebound, wrong axis rejection, swallowing remaining gestures after two-finger cancellation. Native component assertion passes.
-[x] Layout backup, restore to default and undo: save proportions and four-way positions, button/panel/sidebar/status options; apply once after strict verification, without modifying system resolution, permissions, target monitors or avoidance rules.
-[x] Review service window restoration, lock screen/halt screen hiding and input cancellation path; retain event-driven checks and do not add polling keepalive. Switching to permanent application, unlocking and restoring, and coexistence with the original sidebar are still listed as real devices for testing.
-[x] Configuration version 4, application update log, same-signed APK and verification records; 18 JVM tests and 119 native component assertions passed, Lint 0 errors.

## 0.5.0: Personalization and overall return (implementation and local acceptance completed, Samsung real machine to be tested)

-[x] One-handed mode: Default, left and right hand switchable, fixed keys, favorite bar and slider are arranged side by side, maintaining the safety boundary of the special-shaped area and the dual entrance sequence; the vertical fixed keys are still at the bottom.
-[x] Application direction memory: The user configures the direction and executes it once when the tool is explicitly opened; recheck the external screen status after the asynchronous completion, and does not repeatedly grab the direction in the background. The actual effect of Shizuku needs to be confirmed on the real machine.
-[x] The status bar is minimalist/daily/all preset, and still retains independent switches; after manual changes, the customized status is accurately displayed.
-[x] Configuration version 5, layout version 2 override new preferences, compatible with configurations 1–4; atomic rejection of bad data, permissions and display selection remain determined by current device.
-[x] Executable local regression: 19 JVM tests, 147 main interface assertions, another 14 for phone permission grant/revoke and Wi-Fi disconnect/reconnect, for a total of 161 native assertions; Lint 0 errors, 16 warnings. Contains 2–5 keys, four corners, multiple sizes, state strategies and configuration recovery, does not replace real front and back switching, no network or Samsung permission testing.
-[x] See `dist/Development/VALIDATION.md` for the application update log, APK with the same package name and signature, native screenshots and final verification list.

## 0.6.0: Real device layout feedback and full interface density check

-[x] Notification changes are changed to update the card in place without rebuilding the notification panel window; supplementary testing frequently repeats notification, modification, addition, removal and permission loss paths.
-[x] Default transparent contrast stroke, retain pure black/light/dark options; status with safe outer edge filling, no screenshot color selection.
-[x] The status bar defaults to 70%, providing 50%–150% slider and independent percentage switch; forced battery percentage on the left side of the control center.
-[x] Based on feedback from users who have turned off Samsung's external screen navigation bar, edge capture is enabled by default; the double horizontal bars also reach the outermost edge when retracted. Keep optional inward ducking and 0–48dp offset, tap entrance.
-[x] Tolerate diagonal inward sliding and short swipes, cancel and reverse retract without accidentally touching the button; content and background are calculated separately, and the extra reserved band at the bottom of the panel is cancelled.
-[x] Notify first row merging, control center status row deduplication, set row height and spacing compression; record available content height, true empty status and DPI column swapping results page by page.
-[x] Tighten icons, spacing and search lines in the application center; auto-column arrangement by available width/DPI, edit/settings menus and expand keys merged.
-[x] App build, 22 JVM tests, 189 main component assertions, 30 page-by-page assertions each at 340/440 dpi; light and dark background with 50%–150% size checks, same-signed APK.
-[ ] Mobile phone retest: success rate of edge-to-edge swiping, cross-window tracking, notification update flashing and bottom exposure, actual position and 70% status bar readability.

## Still needs to be confirmed by real machine

-[ ] Samsung frontend/input method window reporting, desktop avoidance, cross-application persistence and screen unlock recovery.
-[ ] Four-directional calibration of special-shaped borders, real hand touch, and left and right hand operation comfort coexist with the original sidebar.
-[ ] Shizuku rotation/brightness, per-app orientation, external screen routing for system notifications and third-party tiles.
-[ ] Actual behavior of status information, dual-SIM signal, background blur and system file picker import and export.
The phone is currently not connected, and the above is reserved for verification; the limited automatic iteration has ended, and the original scheduled task has been suspended. Actual feedback will be received before continuing.

## Acceptance and iteration agreement

### 0.7: Native compact details and notification folding

For details, see the actual implementation and boundaries at the top of `DESIGN-0.7.0.md`; after native access, acceptance will be based on local verification and real machine capabilities, and suspended scheduled tasks will not be automatically resumed.

-[x] Notification icon, title/text single-line folding, real overflow display expansion key, retain notification key corresponding status; local native check.
-[x] The control center unified long-press details; the width and height of all self-drawn secondary pop-up windows are tightened according to the content, the safe area only has an upper limit, long names are wrapped, and super-high lists are scrolled.
-[x] The self-drawn details are connected to the native RenderEffect, and the system fuzzy monitoring is used across windows; it falls back when the system does not support it or when power is saved, and the attribute animation respects the system switch. Actual machine performance has not been tested.
-[x] The automatic rotation of external screen tools and the manual/application rule arbitration code have been connected; long pressing the slider will not accidentally change the volume through the simulator. Sensors and four-way physical mapping await real devices.
-[ ] Detect Wi-Fi reading/connection, media routing, and system entrance external screen display respectively, without preview interaction instead of real machine capability verification.
Each delivery round records actual versions, changes, test coverage, failures or unverified items. Failed builds, fictitious states, crossing gaps, inability to cancel gestures, or accidentally touching the app do not count as completion. The simulator proves native component behavior and does not replace the real-device conclusions of One UI external screen routing, system notifications, blur, cellular signal, and background persistence.

The phone is currently not connected. Implementation and local verification that can be completed independently continue to advance; matters that really require real machine results are clearly listed, so there is no need to repeat the construction to cover up missing evidence.