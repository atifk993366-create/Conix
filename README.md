# JARVIS Android — Starter Build 0.1

This is a compilable starter scaffold, not the complete autonomous assistant. It provides a Compose UI, typed commands, Android speech-recognition activity integration, Android TTS, and a few safe demo commands.

## Important limitations in this build
- No always-listening wake-word engine is connected. The wake phrase field is currently a setting placeholder.
- No speaker-verification model is connected. This build cannot distinguish your voice from another person's voice. Do not use it for private or sensitive actions.
- Speech recognition language availability depends on the Android speech provider and installed language packs. The initial voice intent is configured for en-IN. Hindi/Odia selection and dedicated Odia ASR/TTS adapters are future work.
- No e-commerce checkout, WhatsApp sending, call placement, background microphone service, or game-control automation is implemented yet.
- No cloud AI model is connected. The chat currently uses a small local command router.

## Build APK using only an Android phone
1. Create a GitHub account and a new repository named `JARVIS-Android`.
2. Extract this ZIP on your phone (Files app or ZArchiver).
3. Upload the extracted project files and folders to the repository, preserving folder paths. Alternatively use GitHub's web upload for files.
4. Open the repository's **Actions** tab.
5. Enable Actions if GitHub asks.
6. Run **Build JARVIS APK** using **Run workflow**, or push a commit to `main`.
7. Open the successful workflow run, scroll to **Artifacts**, and download `JARVIS-debug-apk`.
8. Extract the downloaded artifact ZIP; install `app-debug.apk`. Android may ask permission to install unknown apps from your browser/files app.

## Basic demo commands
- `open youtube`
- `open google`
- `open settings`
- `search weather in Bhubaneswar`

## Next implementation milestones
1. Add true wake-word detection and a visible foreground microphone service with correct Android lifecycle handling.
2. Add speaker verification with an on-device model, enrollment, anti-replay checks, and fail-closed authorization.
3. Add English/Hindi/Odia ASR and TTS adapters, tested on the target phones.
4. Add tool registry, explicit permission tiers, call/SMS/app integrations, web research, shopping, and memory.

## Multiple phones
This starter app is installed independently on each phone. Shared account/memory sync and device enrollment are not implemented. Do not copy a voice profile insecurely between devices; implement authenticated enrollment and encrypted synchronization later.
