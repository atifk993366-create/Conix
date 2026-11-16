# Kori cloud call agent (optional, costs money)

Android cannot let an app speak into an ordinary SIM call, so a spoken AI conversation with the caller needs
**carrier call forwarding + a telephony number + a server**. This folder is a small, working-in-principle starter.
It was only tested locally with simulated webhook requests (no real phone call, no real provider).

1. Pick a provider that gives you an inbound number where you live and check price, caller-ID, forwarding charges,
   recording rules and data location first (Twilio, Plivo, Exotel ...). India needs regulatory paperwork for numbers.
2. Run `server.py` on any host that runs Python (a small VPS, Render, Koyeb ...). Set the environment variables
   listed at the top of the file. `KORI_TOKEN` must be a long random secret.
3. In the provider console set the voice webhook of your number to `https://YOUR-HOST/voice` (HTTP POST).
   For Twilio also set `TWILIO_AUTH_TOKEN` and `PUBLIC_URL` so only Twilio can call your server.
4. In Kori > Call Assistant: enter the server address and the token, switch on sync, tap "Sync now".
5. In Kori > Call Assistant > Call forwarding: forward unanswered / busy / unreachable calls to your provider number.
6. Test with a separate test number and people who agree to it before using it on your main number.

Privacy: the greeting tells the caller it is an AI and that the call may be transcribed. Audio is not stored; only the
transcript and a summary are, in `notes.json`. Delete it whenever you like. Keep the server behind HTTPS.
