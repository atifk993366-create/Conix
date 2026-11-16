"""
Step 1 - clean, de-identify and split the command dataset.

Input : one or more CSV files with columns text,intent  (seed_intents.csv and your exported kori_intents_*.csv)
Output: train.csv / val.csv / test.csv (80/10/10) and dataset_report.json

Near-duplicates (same sentence apart from filler words and numbers) always land in the SAME split,
so the test set does not secretly contain training sentences.
"""
import csv, hashlib, json, re, sys, collections

FILES = sys.argv[1:] or ["seed_intents.csv"]
FILLER = re.compile(r"\b(hey kori|kori|please|jaldi|zara|na|ji)\b", re.I)


def redact(t):
    t = re.sub(r"[\w.+-]+@[\w-]+\.[\w.]+", "<EMAIL>", t)                  # e-mail addresses
    t = re.sub(r"\+?\d[\d\s-]{6,}\d", "<NUM>", t)                          # phone numbers, long codes, OTP-like runs
    return t.strip()


def norm_digits(t):
    return "".join("0" if ch.isdecimal() else ch for ch in t.lower())


def group_key(t):
    k = FILLER.sub(" ", norm_digits(t))
    k = re.sub(r"[^\w\s]", " ", k)
    return re.sub(r"\s+", " ", k).strip()


rows, seen = [], set()
for f in FILES:
    with open(f, encoding="utf-8", newline="") as fh:
        for r in csv.DictReader(fh):
            text, intent = redact(r["text"]), r["intent"].strip().lower()
            if not text or not intent or (text, intent) in seen:
                continue
            seen.add((text, intent))
            rows.append((text, intent))

splits = {"train": [], "val": [], "test": []}
for text, intent in rows:
    h = int(hashlib.sha1(group_key(text).encode("utf-8")).hexdigest(), 16) % 100
    splits["train" if h < 80 else "val" if h < 90 else "test"].append((text, intent))

for name, items in splits.items():
    with open(f"{name}.csv", "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["text", "intent"])
        w.writerows(items)

report = {
    "examples": len(rows),
    "per_split": {k: len(v) for k, v in splits.items()},
    "per_intent": dict(collections.Counter(i for _, i in rows)),
    "files": FILES,
}
json.dump(report, open("dataset_report.json", "w", encoding="utf-8"), indent=2, ensure_ascii=False)
print(json.dumps(report, indent=2, ensure_ascii=False))
