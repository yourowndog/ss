# ScreenSense: from hardware shortcut to speech

Goal: one deliberate shortcut acknowledges with a tone, reads the visible screen, and stops speech on the next activation. Ordinary reading should run on the phone without ADB or a cluster speech server.

## Pipeline

1. Samsung assigns Side/Power + Volume Up to ScreenSense. This system shortcut is separate from the app's optional Volume Up/Down detector. ScreenSense does not intercept the power button itself.
2. Android binds the enabled accessibility service. Its accessibility-button callback sends ACTION_TOGGLE_READ to the foreground reader service.
3. ScreenSenseService starts a read job and emits the acknowledgement tone. A second toggle cancels the job and speech.
4. The accessibility tree supplies visible, non-password text, ordered by screen position. Custom-drawn apps and terminals may expose little usable text.
5. If text is sparse, Android 11+ accessibility takes one screenshot, and Google ML Kit recognizes text on-device. The service must declare canTakeScreenshot. This is different from continuous screen sharing.
6. Phone voice uses Android TextToSpeech. Sam Voice uses the configured cluster HTTPS speech service and needs connectivity/authentication. Kokoro needs local model files and storage access.
7. Android routes audio to the active output, which may be the vehicle/headset over Bluetooth rather than the phone speaker.

## Permission distinction

Android 14+ requires fresh consent for each MediaProjection session. That consent cannot be made permanent by repeatedly granting it. Accessibility is the appropriate persistent authorization for on-demand reading.

Enabled authorization is not proof that a service is connected, a shortcut is assigned, the installed app supports screenshots, the voice is ready, or audio reaches the intended output. Diagnose those layers separately.

## Initial evidence: November 13, 2026

- Phone: Samsung SM-S938U, Android 16; Operator Android adapter can reach it through ADB TCP port 5555.
- Installed app initially: 1.0.0 / code 1, last updated September 29.
- Accessibility was already enabled and bound. This inspection did not establish recurring accessibility revocation.
- Installed service initially advertised retrieve-window-content only, with key filtering disabled; newer source declares screenshot and key-filtering capabilities.
- Samsung direct-access and volume shortcut targets initially returned null; the floating accessibility button was assigned.
- Stored preferences selected the cluster voice; the newer use_device setting was absent.
- Media audio was unmuted and routed to Bluetooth A2DP during inspection.
- Git default is master, not main. The checkpoint branch is ancestral to the newer Watch/audio-focus branch. Consolidate with a normal fast-forward/merge after checks; preserve history.

## Reliability boundaries

- This reads visible content. It does not automatically scroll a complete conversation or terminal history.
- Current reader silently caps extracted text at 3,000 characters. Long-form reading needs chunking.
- Secure windows and lock-screen state can block screenshots. Do not use ADB as the ordinary reading dependency.
- Phone TextToSpeech error callbacks currently complete the coroutine without throwing. A returned method is not proof of successful playback.
- Initial phone voice lacked audio-focus handling. A concurrent repair has added accessibility speech attributes and transient audio focus in source; verify the final installed build and music/navigation coexistence.
- Validate the physical Samsung shortcut, cold start, process restart, reboot/unlock, Bluetooth output, repeated read/stop, OCR fallback, and provider failures separately. A green build proves none of these by itself.
- General keylogging is unnecessary. Log triggers, lifecycle, character counts, provider state, and errors; avoid screen contents and arbitrary typed text.

## Execution

Build on Sisyphus in build.slice; Sleeper coordinates. Multiple sessions were actively sharing this checkout during investigation: coordinate before editing, merging, or installing. Preserve application data and signing identity; do not uninstall to bypass a signing mismatch. Do not reboot the phone or take over navigation during unattended testing.

## Platform references

- https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- https://developer.android.com/media/grow/media-projection
- https://www.samsung.com/us/support/answer/ANS10001906/

## Verified repair outcome: November 13, 2026

- Build and Android lint passed on Sisyphus for the repaired source and phone-specific packaging: 0 errors, 63 warnings. Latest tested executable source revision: f721677; subsequent e1dae3d adds README documentation only.
- The phone now reports version 1.1.0 / code 2. The successful streamed installation preserved application data.
- Accessibility rebound after the update without another manual grant. Android reports capabilities 137 (window text, filtered keys, screenshots), requestFilterKeyEvents=true, and no crashed ScreenSense service.
- Samsung direct-access target is ScreenSense. A remote accessibility-button action reached the installed callback, extracted 1,648 characters, and reported Android speech playback starting. A subsequent action cancelled the read.
- A separate live trigger found zero accessibility text nodes, successfully used screenshot OCR to extract 248 characters, and reported Android speech playback starting. The captured screen's contents were not copied into this record.
- The reader is foreground with mediaPlayback type. Its default phone voice was used without requiring the cluster speech endpoint.
- All pre-existing checkpoint/Watch branch commits were consolidated by normal fast-forward into GitHub master (e1dae3d at consolidation); the feature branch was published too. No branches were deleted.
- Actual physical Power + Volume Up operation, sound reaching the vehicle, reboot recovery, and prolonged use remain unverified. Remote speech-start callbacks do not prove acoustic output.
- The 3,000-character cap and lack of automatic scrolling remain documented limitations.

## Verified repair results

- Installed 1.1.0/code2 in place with the existing signing certificate; no uninstall or data reset. ARM64 package is 51 MiB (universal was 149 MiB). `adb install --no-incremental -r` succeeded; incremental installation was unreliable on this wireless link.
- The new accessibility service remained enabled and bound, requesting key filtering with capabilities 137 (window content, key filtering, screenshots). Samsung Power + Volume Up target is assigned to ScreenSense.
- Injected Power + Volume Up produced the accessibility-button callback, extracted 1,648 characters from ChatGPT, and started Android speech 213 ms after the read trigger. A second activation cancelled it. This verifies Android event handling, not physical switch feel or vehicle acoustics.
- On a launcher screen with zero accessibility text nodes, the app captured an accessibility screenshot, completed on-device OCR (248 characters), and started Android speech. No MediaProjection authorization dialog or ADB screenshot transport was used for that read.
- Remote Sam synthesis originally returned HTTP 500 because the old process reported CUDA unavailable. A fresh process saw CUDA. Restarting only isis-voxcpm-speech.service restored authenticated synthesis: HTTP 200, valid WAV, 215,084 bytes, 3.8 seconds. No machine reboot/GPU reset.
- Sisyphus assembleDebug + lintDebug passed at e1dae3d. Target SDK remains 34; the compile toolchain is updated separately. There is no existing unit-test suite in this repository.

- Full launcher OCR read reached Android speech completion (248 characters). A controlled SIGKILL of only the app process was followed by Android automatically recreating the foreground service, reconnecting accessibility, and initializing phone TTS in under two seconds without opening ScreenSense or granting permissions again. This is process recovery, not a reboot/force-stop guarantee.

- Settings UI checked on the phone: Ready via Accessibility, Accessibility Enabled, Phone voice selected. Speak Custom Text (blank/default sample) started and completed speech; Test Read Screen extracted 308 characters and started speech; a second tap cancelled. Returned foreground to ChatGPT afterward.
