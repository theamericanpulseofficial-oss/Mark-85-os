# J.A.R.V.I.S. — Background Voice AI Phone Agent

A native Android background voice AI assistant powered by the **NVIDIA NIM / NVIDIA API** and local wake-word detection. 

Press **START JARVIS** once, and JARVIS runs in the background as an Android foreground service waiting for **"Hey Jarvis"**.

---

## 🚀 Quick Setup & Build Guide (Google AI Studio → GitHub → APK)

### 1. Export the Project
Export or download the complete project files from Google AI Studio.

### 2. Push to GitHub
Create a new GitHub repository and push the code:
```bash
git init
git add .
git commit -m "Initial commit: JARVIS Background Voice AI Agent"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/JARVIS.git
git push -u origin main
```

### 3. Open GitHub Actions
Navigate to the **Actions** tab in your GitHub repository.

### 4. Run Build
The `.github/workflows/build.yml` workflow automatically runs on push or can be triggered manually via **Run workflow**.

### 5. Download DEBUG APK
Once the workflow finishes, go to the workflow summary page and download the **`jarvis-debug-apk`** artifact.

### 6. Install APK
Unzip the downloaded artifact and install the generated APK on your Android device (ensure "Install unknown apps" is enabled).

### 7. Enter NVIDIA API Key
Open JARVIS, tap the **Settings** icon (top-right), and paste your NVIDIA API key (e.g. `nvapi-...`).

### 8. Enter Picovoice AccessKey (Optional)
In **Settings → Wake Word**, optionally enter your Picovoice Porcupine AccessKey or use the built-in local voice activity detection.

### 9. Grant Permissions
In **Settings → Permissions** (or on prompt), grant the required Android permissions:
- **Microphone (`RECORD_AUDIO`)**: For voice capture and wake word.
- **Notifications (`POST_NOTIFICATIONS`)**: For background service persistence.
- **Phone Calls (`CALL_PHONE`)**: Optional, for voice-activated direct calls.
- **Contacts (`READ_CONTACTS`)**: Optional, for searching contact numbers.

### 10. Start JARVIS
Return to the main screen and tap **START JARVIS**. The glowing Arc Reactor will activate and the status will transition to **`LISTENING FOR "HEY JARVIS"`**.

### 11. Say "Hey Jarvis"
Say **"Hey Jarvis"** anytime, even while using other apps or with the screen active. JARVIS will capture your voice command, reason through the NVIDIA NIM agent model, trigger the appropriate phone tool, and speak the response.

---

## 🛠 Extensible Phone Control Tools

JARVIS uses an extensible `PhoneTool` architecture mapped to official Android intents and APIs:

| Tool | Trigger Examples | Description | Confirmation |
| :--- | :--- | :--- | :---: |
| **`OpenAppTool`** | *"Open YouTube"*, *"Open YouTube Studio"*, *"Open Spotify"* | Launches installed Android applications by name or package | No |
| **`LaunchUrlTool`** | *"Open github.com"*, *"Go to nvidia.com"* | Opens web URLs in the default browser | No |
| **`PhoneCallTool`** | *"Call Mom"*, *"Dial +1234567890"* | Dials or calls contacts | **Yes (Confirmation Required)** |
| **`AlarmTool`** | *"Set an alarm for 7:00 AM"* | Sets an alarm via `AlarmClock.ACTION_SET_ALARM` | No |
| **`TimerTool`** | *"Set a 10 minute timer"* | Sets a countdown timer via `AlarmClock.ACTION_SET_TIMER` | No |
| **`MediaTool`** | *"Pause music"*, *"Next track"*, *"Play"* | Controls device media playback | No |
| **`NotificationTool`** | *"Remind me to buy groceries"* | Posts reminder notifications in the shade | No |
| **`SettingsTool`** | *"Open Wi-Fi settings"*, *"Open Bluetooth"* | Opens device setting panels | No |
| **`ContactsTool`** | *"What is John's phone number?"* | Looks up contact information from the address book | No |
| **`WebActionTool`** | *"Search YouTube for quantum computing"* | Searches Google or YouTube | No |

---

## 🔒 Security & Privacy

- **Encrypted Local Storage**: API keys and tokens are encrypted locally using AES-256-GCM via the Android KeyStore (`SecurePreferencesHelper`).
- **No Hardcoded Secrets**: Secrets are never hardcoded or committed to git.
- **Local Wake-Word Processing**: Wake-word monitoring is processed 100% locally on-device. Microphone audio is never streamed to external servers while in standby.
- **Sensitive Action Verification**: Sensitive actions (placing direct calls, publishing content, changing accounts) require explicit spoken user confirmation before execution.
- **Lock-Screen Awareness**: If the device is locked (`KeyguardManager.isKeyguardLocked`), sensitive actions request unlocking first.

---

## 🏗 Architecture

```
JARVIS/
├── app/
│   ├── src/main/java/com/example/
│   │   ├── ai/                  # AIProvider & NvidiaProvider networking isolation
│   │   ├── agent/               # JarvisAgent reasoning, multi-step tools & states
│   │   ├── audio/               # Headless STT & customizable TTS engines
│   │   ├── wakeword/            # Local WakeWordDetector
│   │   ├── tools/               # PhoneTool implementations & ToolRegistry
│   │   ├── service/             # JarvisForegroundService (Microphone type)
│   │   ├── security/            # SecurePreferencesHelper (Keystore + AES-GCM)
│   │   ├── settings/            # JarvisSettings data model
│   │   ├── ui/                  # Compose MainScreen, SettingsScreen, ViewModel
│   │   └── MainActivity.kt      # Main Entrypoint & Permission handling
│   ├── src/main/res/            # Futuristic icons, layout resources & strings
│   └── AndroidManifest.xml      # Permissions, queries, service declarations
├── .github/workflows/build.yml  # GitHub Actions CI workflow
├── build.gradle.kts             # Root Gradle build script
└── settings.gradle.kts          # Project settings
```

---

## 📦 Building Locally

```bash
./gradlew assembleDebug
```
The resulting debug APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`
