"""Generates training/seed_intents.csv: hand-authored command templates in English, Hinglish, Hindi and Odia.
Add your own real examples (Settings > Training > Export intent dataset) and retrain."""
import csv, random

random.seed(7)
rows = []

def add(intent, *texts):
    for t in texts:
        rows.append((t, intent))

NUMS = [2, 3, 5, 7, 10, 12, 15, 20, 25, 30, 45]
for n in NUMS:
    add("timer",
        f"set a timer for {n} minutes", f"timer for {n} minutes please", f"start a {n} minute timer",
        f"remind me in {n} minutes", f"countdown {n} minutes",
        f"ek timer {n} minute ka lagao", f"{n} minute ka timer set karo", f"{n} minute baad yaad dilana", f"timer lagao {n} minute",
        f"{n} मिनट का टाइमर लगाओ", f"टाइमर सेट करो {n} मिनट के लिए", f"{n} मिनट बाद बताना",
        f"{n} ମିନିଟ୍ ପାଇଁ ଟାଇମର୍ ଲଗାଅ", f"ଟାଇମର୍ {n} ମିନିଟ୍ ସେଟ୍ କର")
for h in [5, 6, 7, 8, 9, 10, 11, 12]:
    add("alarm",
        f"set an alarm for {h} am", f"wake me up at {h}:30", f"alarm at {h} pm", f"set alarm for {h}:15 in the morning",
        f"subah {h} baje ka alarm lagao", f"alarm set karo {h} baje", f"mujhe {h} baje utha dena", f"shaam {h} baje alarm lagana",
        f"सुबह {h} बजे का अलार्म लगाओ", f"{h} बजे उठा देना", f"अलार्म लगाओ {h} बजे",
        f"ସକାଳ {h} ଟାରେ ଆଲାର୍ମ ଲଗାଅ", f"{h} ଟାରେ ମୋତେ ଉଠାଅ")
add("flashlight",
    "turn on the flashlight", "flashlight on", "torch on", "turn off the torch", "flashlight off", "switch on the flashlight", "switch off the torch",
    "torch jalao", "torch band karo", "flashlight chalu karo", "flashlight band karo", "torch on karo", "torch off kar do",
    "टॉर्च जलाओ", "टॉर्च बंद करो", "फ्लैशलाइट चालू करो", "फ्लैशलाइट बंद करो",
    "ଟର୍ଚ୍ଚ ଜଳାଅ", "ଟର୍ଚ୍ଚ ବନ୍ଦ କର", "ଫ୍ଲାଶଲାଇଟ୍ ଚାଲୁ କର", "ଫ୍ଲାଶଲାଇଟ୍ ବନ୍ଦ କର")
add("volume",
    "volume up", "increase the volume", "turn the volume down", "mute the phone", "make it louder", "lower the volume", "volume down please", "mute",
    "volume badhao", "awaaz kam karo", "volume kam karo", "mute kar do", "awaaz badhao", "awaaz tez karo", "phone ko chup kara do",
    "आवाज़ बढ़ाओ", "आवाज़ कम करो", "वॉल्यूम बढ़ाओ", "म्यूट करो",
    "ଭଲ୍ୟୁମ୍ ବଢ଼ାଅ", "ଭଲ୍ୟୁମ୍ କମାଅ", "ମ୍ୟୁଟ୍ କର")
add("music",
    "pause the music", "play music", "next song", "previous song", "skip this song", "resume the song", "stop the music", "play the next track", "go back to the previous song",
    "gaana roko", "agla gaana lagao", "gaana chalao", "pichla gaana", "gaana band karo", "gaana pause karo", "agla gana",
    "गाना रोको", "अगला गाना", "गाना चलाओ", "पिछला गाना", "गाना बंद करो",
    "ଗୀତ ବନ୍ଦ କର", "ପରବର୍ତ୍ତୀ ଗୀତ ଚଳାଅ", "ଗୀତ ଚଳାଅ", "ଆଗକୁ ଗୀତ")
add("device_info",
    "what is my battery level", "how much battery is left", "battery percentage", "am i connected to wifi", "check my internet connection", "is the internet working", "battery status",
    "battery kitni hai", "battery kitna bacha hai", "internet chal raha hai kya", "wifi connected hai kya", "battery ka status batao",
    "बैटरी कितनी है", "बैटरी कितनी बची है", "इंटरनेट चल रहा है क्या",
    "ବ୍ୟାଟେରୀ କେତେ ଅଛି", "ଇଣ୍ଟରନେଟ୍ ଚାଲୁଛି କି")
APPS = {
    "youtube": ["youtube", "यूट्यूब", "ୟୁଟ୍ୟୁବ୍"], "whatsapp": ["whatsapp", "व्हाट्सएप", "ହ୍ୱାଟସଆପ୍"],
    "instagram": ["instagram", "इंस्टाग्राम", "ଇନଷ୍ଟାଗ୍ରାମ୍"], "chrome": ["chrome", "क्रोम", "କ୍ରୋମ୍"],
    "spotify": ["spotify", "स्पॉटिफाई"], "camera": ["camera", "कैमरा", "କ୍ୟାମେରା"],
    "gallery": ["gallery", "गैलरी"], "calculator": ["calculator", "कैलकुलेटर"], "maps": ["maps", "मैप्स"],
    "settings": ["settings", "सेटिंग"], "gmail": ["gmail", "जीमेल"], "facebook": ["facebook", "फेसबुक"],
    "telegram": ["telegram", "टेलीग्राम"], "snapchat": ["snapchat"], "phonepe": ["phonepe"], "paytm": ["paytm"],
}
for en, names in APPS.items():
    for nm in names:
        add("open_app", f"open {nm}", f"launch {nm}", f"start {nm}", f"{nm} kholo", f"{nm} open karo", f"{nm} khol do", f"{nm} खोलो", f"{nm} ଖୋଲ", f"please open {nm} for me")
add("camera_identify",
    "what is this thing in front of me", "what am i looking at", "identify this", "scan this object", "what is this", "tell me what this is",
    "ye kya hai", "yeh kya cheez hai", "isse pehchano", "ye kaun sa phool hai",
    "ये क्या है", "इसे पहचानो", "ଏହା କଣ ଅଟେ", "ଏହାକୁ ଚିହ୍ନାଅ")
add("driving_on", "i am driving", "driving mode on", "i'm driving right now", "main gaadi chala raha hoon", "drive mode chalu karo", "mai gaadi chala raha hu",
    "मैं गाड़ी चला रहा हूं", "ड्राइविंग मोड चालू करो", "ମୁଁ ଗାଡି ଚଳାଉଛି")
add("driving_off", "i am not driving", "driving mode off", "i'm done driving", "main pahunch gaya", "drive mode band karo", "gaadi rok di hai",
    "मैं पहुंच गया", "ड्राइविंग मोड बंद करो", "ମୁଁ ପହଞ୍ଚିଗଲି")
for n in [5, 10, 15, 30]:
    add("away_on", f"i am busy for {n} minutes", f"away mode on for {n} minutes", f"main {n} minute ke liye busy hoon", f"mujhe {n} minute disturb mat karna",
        f"मैं {n} मिनट के लिए बिजी हूं", f"{n} ମିନିଟ୍ ପାଇଁ ମୁଁ ବ୍ୟସ୍ତ")
add("away_off", "i am back", "away mode off", "i'm available now", "main wapas aa gaya", "main free hoon ab", "मैं वापस आ गया", "ମୁଁ ଫେରିଆସିଲି")
add("game_break", "i am going to the toilet", "game break", "gaming mode", "main toilet ja raha hoon", "do not disturb for two minutes", "मैं टॉयलेट जा रहा हूं")
add("stop_assistant", "stop listening", "stop", "go to sleep", "that's all thanks stop", "band karo", "sunna band karo", "bas karo", "ruk jao", "chup ho jao",
    "चुप हो जाओ", "सुनना बंद करो", "बस करो", "ଶୁଣିବା ବନ୍ଦ କର", "ଚୁପ୍ ହୁଅ")
add("question",
    "what is the capital of france", "who won the match yesterday", "what is the price of gold today", "how does photosynthesis work", "who is the prime minister of india",
    "what is the weather in bhubaneswar", "is it going to rain today", "how tall is mount everest", "explain quantum computing simply", "latest news about cricket", "who wrote the ramayana",
    "bharat ki rajdhani kya hai", "sone ka rate kya hai", "aaj barish hogi kya", "cricket match kaun jeeta", "taj mahal kab bana tha", "kal ka mausam kaisa rahega",
    "क्रिकेट मैच कौन जीता", "आज मौसम कैसा है", "भारत के प्रधानमंत्री कौन हैं", "सोने का भाव क्या है",
    "ଆଜି ପାଗ କେମିତି ଅଛି", "ଓଡ଼ିଶାର ରାଜଧାନୀ କଣ", "ଆଜିର ଖବର କୁହ")
add("chat", "hello", "hi kori", "how are you", "good morning", "thank you", "tell me a joke", "what can you do", "tell me your abilities", "who are you", "you are great",
    "namaste", "kaise ho", "dhanyavaad", "shukriya", "tum kaun ho", "mujhe ek mazedaar baat batao", "good night kori",
    "नमस्ते", "कैसे हो", "शुक्रिया", "तुम कौन हो", "ନମସ୍କାର", "କେମିତି ଅଛ", "ଧନ୍ୟବାଦ")
add("message",
    "send a message to rahul saying i am late", "whatsapp mom that i am coming", "text papa that i reached", "reply to the last message", "send sms to amit hello",
    "rahul ko message bhejo ki main late hoon", "mummy ko whatsapp karo ki main aa raha hoon", "papa ko sms bhejo", "amit ko bolo ki kal milte hain",
    "राहुल को मैसेज भेजो कि मैं लेट हूं", "मम्मी को व्हाट्सएप करो", "ଭାଇକୁ ମେସେଜ୍ ପଠାଅ", "ବାପାଙ୍କୁ ମେସେଜ୍ କର")
add("call", "call rahul", "phone mom", "dial papa", "call my brother", "ring amit", "make a call to the doctor",
    "rahul ko call karo", "mummy ko phone lagao", "papa ko call lagao", "bhai ko phone karo", "doctor ko call karo",
    "राहुल को कॉल करो", "मम्मी को फोन लगाओ", "ମୋ ଭାଇକୁ କଲ୍ କର", "ବାପାଙ୍କୁ ଫୋନ୍ କର")
add("youtube", "play arijit singh songs on youtube", "search cooking videos on youtube", "youtube pe song chalao", "youtube par bhajan lagao", "play a funny video",
    "play lofi music on youtube", "arijit ke gaane youtube par chalao", "यूट्यूब पर गाना चलाओ", "ୟୁଟ୍ୟୁବ୍ ରେ ଗୀତ ଚଳାଅ")
add("web_search", "search for best phones under twenty thousand", "google the nearest hospital", "find hotels near me", "search how to tie a tie", "internet par search karo recipes",
    "google karo best laptop", "सर्च करो सबसे अच्छा फोन", "ଖୋଜ ସର୍ବୋତ୍ତମ ଫୋନ୍")
add("shop", "buy running shoes on amazon", "search headphones on flipkart", "amazon par shoes dhundo", "flipkart par mobile dikhao", "order a phone cover",
    "अमेज़न पर जूते ढूंढो", "ଆମାଜନ୍ ରେ ଜୋତା ଖୋଜ")

add("driving_off", "ड्राइविंग बंद", "गाड़ी रोक दी", "ଗାଡି ଚଳାଇବା ବନ୍ଦ", "ମୁଁ ପହଞ୍ଚି ଗଲି", "drive mode off kar do", "main ghar pahunch gaya", "ड्राइविंग मोड ऑफ करो", "ଡ୍ରାଇଭିଂ ମୋଡ୍ ବନ୍ଦ କର")
add("driving_on", "ड्राइविंग मोड ऑन", "ଡ୍ରାଇଭିଂ ମୋଡ୍ ଚାଲୁ କର", "main drive kar raha hoon", "मैं ड्राइव कर रहा हूं", "ମୁଁ ଡ୍ରାଇଭ୍ କରୁଛି")
add("stop_assistant", "रुक जाओ", "बंद करो", "ଚୁପ୍ ରୁହ", "ଥମିଯାଅ", "sleep mode", "chup raho", "बस रुको", "सो जाओ", "ଶୋଇପଡ଼")
add("camera_identify", "ye kaun sa phool hai", "ye kaun sa jaanwar hai", "ye kya fal hai", "इसका नाम क्या है", "ଏହାର ନାମ କଣ", "what kind of plant is this", "what animal is this", "what is this object in my hand", "ये कौन सा फूल है", "ଏହା କେଉଁ ଫୁଲ")
add("web_search", "खोजो सबसे अच्छा फोन", "ଫୋନ୍ ଖୋଜ", "search best restaurants", "google karo", "search karo", "इंटरनेट पर खोजो", "ଇଣ୍ଟରନେଟ୍ ରେ ଖୋଜ", "find cheap flights", "look up train timings")
add("call", "mummy ko call lagao", "भाई को फोन करो", "पापा को कॉल लगाओ", "ମାଙ୍କୁ କଲ୍ କର", "ଭାଇକୁ ଫୋନ୍ ଲଗାଅ", "call the plumber", "call home", "ghar phone lagao")
add("chat", "ଶୁଭ ସକାଳ", "सुप्रभात", "good evening", "kya haal hai", "क्या हाल है", "ମୋତେ ଏକ ଗପ କୁହ", "tell me a story")

# light augmentation for small classes (polite words and fillers, like real speech)
import collections as _c
counts = _c.Counter(i for _, i in rows)
PRE = ["", "please ", "hey kori ", "kori ", "zara "]
SUF = ["", " please", " jaldi", " na", " ji"]
aug = []
for t, i in rows:
    if counts[i] < 60:
        for _ in range(3):
            aug.append((random.choice(PRE) + t + random.choice(SUF), i))
rows.extend(aug)

seen = set()
out = []
for t, i in rows:
    if (t, i) not in seen:
        seen.add((t, i)); out.append((t, i))
random.shuffle(out)
with open("/tmp/CONIX-Android/training/seed_intents.csv", "w", encoding="utf-8", newline="") as f:
    w = csv.writer(f)
    w.writerow(["text", "intent"])
    w.writerows(out)
import collections
c = collections.Counter(i for _, i in out)
print(len(out), "examples;", dict(c))
