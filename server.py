"""
Kori AI call agent - starter backend (Twilio-style voice webhooks, standard library only).

How it fits together
  caller -> your carrier forwards unanswered/busy calls -> telephony number (Twilio, Plivo, Exotel ...)
         -> this server (/voice, /turn)  -> short AI conversation (name, reason, urgency)
         -> summary saved -> Kori app downloads it from /notes

The caller is told right at the start that they are talking to an AI assistant and that the call may be
transcribed. Nothing is recorded: only the speech-to-text transcript and a summary are kept.

Run:   python server.py            (needs Python 3.9+)
Env:   OWNER_NAME, KORI_TOKEN (secret for the app), PUBLIC_URL, TWILIO_AUTH_TOKEN (validates webhooks),
       LLM_BASE, LLM_KEY, LLM_MODEL (any OpenAI-compatible API, e.g. Groq / OpenRouter), SPEECH_LANG, MAX_TURNS, PORT
"""
import base64, hashlib, hmac, json, os, threading, time, urllib.parse, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from xml.sax.saxutils import escape

OWNER = os.environ.get("OWNER_NAME", "the owner")
TOKEN = os.environ.get("KORI_TOKEN", "")
PUBLIC_URL = os.environ.get("PUBLIC_URL", "").rstrip("/")
TWILIO_AUTH_TOKEN = os.environ.get("TWILIO_AUTH_TOKEN", "")
LLM_BASE = os.environ.get("LLM_BASE", "https://api.groq.com/openai/v1").rstrip("/")
LLM_KEY = os.environ.get("LLM_KEY", "")
LLM_MODEL = os.environ.get("LLM_MODEL", "llama-3.3-70b-versatile")
LANG = os.environ.get("SPEECH_LANG", "en-IN")
MAX_TURNS = int(os.environ.get("MAX_TURNS", "3"))
NOTES_FILE = os.environ.get("NOTES_FILE", "notes.json")
PORT = int(os.environ.get("PORT", "8080"))

lock = threading.Lock()
sessions = {}          # CallSid -> {"from": str, "turns": [caller sentences], "t": time}


def load_notes():
    try:
        with open(NOTES_FILE, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return []


def save_note(who, summary, transcript):
    with lock:
        notes = load_notes()
        nid = (notes[-1]["id"] + 1) if notes else 1
        notes.append({"id": nid, "ts": int(time.time()), "who": who, "summary": summary, "transcript": transcript})
        with open(NOTES_FILE, "w", encoding="utf-8") as f:
            json.dump(notes[-200:], f, ensure_ascii=False)


def llm(system, user, max_tokens=200):
    """Returns text, or None when no key is configured / the call fails."""
    if not LLM_KEY:
        return None
    body = json.dumps({
        "model": LLM_MODEL, "temperature": 0.3, "max_tokens": max_tokens,
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
    }).encode()
    req = urllib.request.Request(LLM_BASE + "/chat/completions", data=body, headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + LLM_KEY})
    try:
        with urllib.request.urlopen(req, timeout=12) as r:
            return json.loads(r.read())["choices"][0]["message"]["content"].strip()
    except Exception as e:
        print("LLM error:", e)
        return None


RULES = (f"You are Kori, a polite AI assistant answering a phone call for {OWNER}, who cannot talk right now. "
         "Collect: the caller's name, why they call, and whether it is urgent. Ask ONE short question at a time. "
         "Never promise anything, never share private information about the owner, never accept or give OTPs, passwords or payment details. "
         "Reply ONLY with JSON: {\"say\": \"what to say next (max 20 words)\", \"done\": true or false}.")


def next_step(turns):
    """Decides what to say next. Falls back to fixed questions when there is no AI key."""
    if len(turns) >= MAX_TURNS:
        return "Thank you. I will pass this on. Goodbye.", True
    text = llm(RULES, "Caller said so far:\n" + "\n".join(f"{i + 1}. {t}" for i, t in enumerate(turns)))
    if text:
        try:
            s, e = text.find("{"), text.rfind("}")
            j = json.loads(text[s:e + 1])
            return str(j.get("say", "")) or "Thank you. Goodbye.", bool(j.get("done"))
        except Exception:
            pass
    fixed = ["Thank you. Is it urgent?", "Thank you. I will pass this on. Goodbye."]
    return fixed[min(len(turns) - 1, 1)], len(turns) >= 2


def summarize(who, turns):
    text = llm("Summarize this message left for the phone owner in 1-2 short sentences. Mention name, reason and urgency.",
              f"Caller number: {who}\n" + "\n".join(turns))
    return text or " / ".join(turns)[:300]


def say(text):
    return f"<Say language=\"{LANG}\">{escape(text)}</Say>"


def gather(prompt):
    return (f"<Gather input=\"speech\" action=\"/turn\" method=\"POST\" speechTimeout=\"auto\" language=\"{LANG}\">{say(prompt)}</Gather>"
            f"{say('I could not hear you. Please send a text message instead. Goodbye.')}<Hangup/>")


def xml(body):
    return f"<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response>{body}</Response>"


def valid_signature(path, params, header):
    """Twilio request signature: HMAC-SHA1 over the full URL plus the sorted POST fields."""
    if not TWILIO_AUTH_TOKEN:
        return True
    data = PUBLIC_URL + path + "".join(k + params[k] for k in sorted(params))
    mac = base64.b64encode(hmac.new(TWILIO_AUTH_TOKEN.encode(), data.encode(), hashlib.sha1).digest()).decode()
    return hmac.compare_digest(mac, header or "")


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="text/xml; charset=utf-8"):
        raw = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        if u.path == "/health":
            return self._send(200, "ok", "text/plain")
        if u.path == "/notes":
            auth = self.headers.get("Authorization", "")
            if not TOKEN or not hmac.compare_digest(auth, "Bearer " + TOKEN):
                return self._send(401, "unauthorized", "text/plain")
            since = int(urllib.parse.parse_qs(u.query).get("since", ["0"])[0] or 0)
            notes = [n for n in load_notes() if n["id"] > since]
            return self._send(200, json.dumps(notes, ensure_ascii=False), "application/json")
        self._send(404, "not found", "text/plain")

    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0") or 0)
        form = {k: v[0] for k, v in urllib.parse.parse_qs(self.rfile.read(n).decode("utf-8")).items()}
        path = urllib.parse.urlparse(self.path).path
        if not valid_signature(path, form, self.headers.get("X-Twilio-Signature")):
            return self._send(403, "bad signature", "text/plain")
        sid, caller = form.get("CallSid", "?"), form.get("From", "unknown")
        if path == "/voice":
            sessions[sid] = {"from": caller, "turns": [], "t": time.time()}
            greeting = (f"Hello, this is Kori, an AI assistant for {OWNER}. {OWNER} cannot take your call right now. "
                        "This call may be transcribed so I can pass on your message. Please tell me your name and why you are calling.")
            return self._send(200, xml(gather(greeting)))
        if path == "/turn":
            s = sessions.setdefault(sid, {"from": caller, "turns": [], "t": time.time()})
            heard = form.get("SpeechResult", "").strip()
            if heard:
                s["turns"].append(heard)
            if not s["turns"]:
                return self._send(200, xml(say("I could not hear you. Please send a text message instead. Goodbye.") + "<Hangup/>"))
            reply, done = next_step(s["turns"])
            if done:
                save_note(s["from"], summarize(s["from"], s["turns"]), " / ".join(s["turns"]))
                sessions.pop(sid, None)
                return self._send(200, xml(say(reply) + "<Hangup/>"))
            return self._send(200, xml(gather(reply)))
        self._send(404, "not found", "text/plain")

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    print(f"Kori call agent on port {PORT} (AI {'on' if LLM_KEY else 'off: fixed questions'}, token {'set' if TOKEN else 'MISSING'})")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
