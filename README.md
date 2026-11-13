# ScreenSense

Read the visible phone screen aloud on demand. Android 11+ uses the enabled accessibility service for text and screenshots; ordinary screen reading does not require a continuous MediaProjection session, ADB connection, microphone, or remote speech server.

## Operation

1. Enable ScreenSense under Android Accessibility / Installed apps once.
2. On Samsung, assign ScreenSense to the **Power + Volume Up** accessibility shortcut. The service requests the accessibility-button callback so the shortcut invokes reading rather than relying on raw power-key interception.
3. Select **Phone voice** for responsive reading without the cluster. Sam voice and optional local Kokoro remain selectable. Download a device voice in Android's Text-to-speech settings if needed; offline availability depends on the installed engine/voice.
4. Press the shortcut on the screen you want read. A short tone acknowledges the read. Press again to cancel. Rapid Volume Down + Volume Up in either order is an additional app-level shortcut; repeated key-down events are ignored.

The app reads only on request. It first reads visible accessibility text in screen order, omitting password fields. If text is insufficient, Android 11+ captures one screenshot using AccessibilityService.takeScreenshot and runs bundled ML Kit OCR on the phone. The bitmap is recycled after OCR. Protected windows and a locked phone can prevent reading. Android 10 retains the consent-based MediaProjection fallback.

Up to 3,000 characters from the current screen are spoken; this does not automatically scroll through an article or terminal history. A negative tone indicates a processing/speech failure. There is no general keystroke logger.

## Permissions and recovery

Accessibility authorization and screen-sharing consent are different things. Android 14+ requires fresh consent for each new MediaProjection session. Keeping an old consent token is not a durable solution. Accessibility screenshots use the enabled accessibility capability instead.

The enabled accessibility service is rebound by Android after ordinary process death/reboot and starts the foreground reader. The reader restores voice preferences even when launched without the settings activity, and promotes cold starts and sticky restarts to foreground. Notification permission makes this state visible. Android/Samsung can still disable accessibility, restrict the app, or stop it after a force-stop; the app cannot override explicit revocation or promise immunity from OS lifecycle policy. Check the actual bound service when diagnosing an apparently enabled setting.

Broad storage access is only for separately installed Kokoro model files. Phone voice and bundled OCR do not require it. Phone speech uses transient audio focus with ducking, cancels on focus loss, and supports cancelling a pending or active read.

## Speech paths

- **Phone voice:** Android TextToSpeech using the phone's chosen engine and installed voice. Screen text stays on the phone, although the chosen engine may itself use network synthesis.
- **Sam:** authenticated OpenAI-compatible TTS on Sisyphus over tailnet HTTPS. This requires the phone's tailnet connection, a configured build credential, and a healthy GPU-backed server. Server failures are separate from accessibility failures.
- **Kokoro:** optional on-device model files and native runtime.
- **Watch speech:** existing Watch integration stays pinned to Sam independently of the reader's selected provider.

The existing Watch HTTP helper listens on **127.0.0.1:9987**. It is phone-local, not a remote ADB server. Other apps on the same phone can reach the existing unauthenticated /speak endpoint; never expose or forward it externally. ADB is an external repair/deployment tool, not required for normal ScreenSense reading. Wireless ADB TCP mode can need rearming after reboot independently of this app.

## Build

Use the designated Android build worker. JDK 17, Android SDK 35 and Build Tools 35.0.1 are required. The standard Gradle 8.14.3 wrapper verifies its distribution checksum. AGP is 8.10.1 and Kotlin is 2.2.0; compile SDK is 35 while target SDK remains 34 (a target-policy migration is separate work).

```sh
systemd-run --user --scope --slice=build.slice --collect \
  ./gradlew :app:assembleDebug :app:lintDebug --no-daemon --console=plain
# For this ARM64 phone, add -PscreensenseAbis=arm64-v8a (51 MiB instead of 149 MiB).
```

Optional Sam configuration is read from environment/local.properties and the existing user-owned token file; do not commit credentials. APKs contain configured client credentials and must not be published as public build artifacts. For an in-place sideload, sign with the installed app's existing signing certificate and use `adb install --no-incremental -r`; do not uninstall to resolve signing mismatches because that would erase app state.

## Diagnostics

Read narrowly scoped logcat tags: ScreenSenseA11y, ScreenSenseService, AndroidTTSProvider, OpenAITTSProvider. New diagnostics report lifecycle, counts, screenshot error codes, and speech start/completion rather than logging captured content. A configured shortcut, enabled grant, bound service, successful text/OCR extraction, and completed speech are separate checkpoints. An injected ADB key test does not prove a physical hardware chord or that sound reached the vehicle's speaker.

See SCREEN-READING-PIPELINE.md for the investigation, evidence, and remaining checks.
