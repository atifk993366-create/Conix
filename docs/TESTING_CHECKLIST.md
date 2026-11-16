# Kori testing checklist (built / installed / verified)

Use Diagnostics > Feature checklist in the app and tick "I tested this on my phone" only after you really did.

| Area | What to test |
|---|---|
| Build | GitHub Actions produces an APK; it installs over the old one; no crash on launch |
| Voice | Mic permission, tap to speak, spoken reply in each language, Stop microphone button |
| Wake word | "Hey Kori" works with the screen on and off; false triggers; battery use over an hour |
| Actions | Calls / messages need the confirmation you chose; switched-off categories are refused |
| Memory | Add, edit and delete a fact; clear chat; erase everything |
| Call screening | Roles granted; known, unknown and hidden numbers; allow and block lists |
| Away / Driving | Ring time respected; text reaches the caller; calling again within 3 minutes rings through |
| Cloud agent | Provider routing, greeting says AI + transcription, summary arrives in Call notes, billing checked |
| Intent model | Self-test passes on the phone; wrong commands fall back to the AI brain |
| Security | No API keys in the repo; data only in app storage and /Kori backup; Pause switch stops call automation |
