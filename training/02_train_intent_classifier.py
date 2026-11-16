"""
Step 2 - train the on-device intent classifier and export it for the Kori app.

Model : character n-gram TF-IDF + multinomial logistic regression (tiny, fast, works offline, no GPU needed).
Output: kori_intent_model.json  (copy to the phone: /Kori/models/ or Settings > Intent model > Import)
        kori_intent_tests.json  (fixed test sentences with expected answers; the app re-runs them on the phone)
        evaluation_report.txt   (accuracy, per-class precision/recall, confusion matrix)
"""
import csv, json, datetime, sys
import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix


def norm(t):
    """Must stay identical to IntentModel.normalize() in the app."""
    return "".join("0" if ch.isdecimal() else ch for ch in t.lower())


def load(name):
    with open(name, encoding="utf-8", newline="") as fh:
        rows = list(csv.DictReader(fh))
    return [r["text"] for r in rows], [r["intent"] for r in rows]


Xtr, ytr = load("train.csv")
Xva, yva = load("val.csv")
Xte, yte = load("test.csv")

vec = TfidfVectorizer(analyzer="char_wb", ngram_range=(2, 4), preprocessor=norm, min_df=2, sublinear_tf=False, norm="l2")
Atr = vec.fit_transform(Xtr)
clf = LogisticRegression(C=20.0, max_iter=4000, class_weight="balanced")
clf.fit(Atr, ytr)


def evaluate(X, y, title):
    p = clf.predict(vec.transform(X))
    return f"{title}: accuracy {accuracy_score(y, p):.3f} on {len(y)} examples\n"


report = evaluate(Xtr, ytr, "train") + evaluate(Xva, yva, "validation") + evaluate(Xte, yte, "test (held out)")
pte = clf.predict(vec.transform(Xte))
report += "\n" + classification_report(yte, pte, zero_division=0)
labels = list(clf.classes_)
report += "\nConfusion matrix (rows = true, columns = predicted)\n" + str(labels) + "\n" + str(confusion_matrix(yte, pte, labels=labels))
errors = [(t, a, b) for t, a, b in zip(Xte, yte, pte) if a != b]
report += "\n\nMistakes to inspect (add balanced examples, then retrain):\n" + "\n".join(f"  {t!r}: expected {a}, got {b}" for t, a, b in errors[:40])
open("evaluation_report.txt", "w", encoding="utf-8").write(report)
print(report)

# confidence threshold: smallest value where predictions on the validation set are >= 97% right
proba = clf.predict_proba(vec.transform(Xva))
best = 0.90
for th in np.arange(0.5, 0.96, 0.05):
    keep = proba.max(axis=1) >= th
    if keep.sum() > 0 and (clf.classes_[proba.argmax(axis=1)][keep] == np.array(yva)[keep]).mean() >= 0.97:
        best = float(round(th, 2))
        break

model = {
    "name": "kori-intent",
    "version": datetime.date.today().isoformat(),
    "dataset": f"{len(Xtr) + len(Xva) + len(Xte)} examples",
    "license": "trained on your own authored data",
    "analyzer": "char_wb",
    "ngram_min": 2,
    "ngram_max": 4,
    "threshold": best,
    "labels": labels,
    "vocab": {k: int(v) for k, v in vec.vocabulary_.items()},
    "idf": [round(float(x), 6) for x in vec.idf_],
    "coef": [[round(float(x), 4) for x in row] for row in clf.coef_],
    "intercept": [round(float(x), 5) for x in clf.intercept_],
}
json.dump(model, open("kori_intent_model.json", "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))

# ---- verify the exported file: re-implement exactly what the Android app does and compare with scikit-learn
import math


def char_wb(text, lo, hi):
    out = []
    for w in " ".join(text.split()).split():
        w = " " + w + " "
        for n in range(lo, hi + 1):
            off = 0
            out.append(w[off:off + n])
            while off + n < len(w):
                off += 1
                out.append(w[off:off + n])
            if off == 0:
                break
    return out


def predict_from_json(m, text):
    counts = {}
    for g in char_wb(norm(text), m["ngram_min"], m["ngram_max"]):
        i = m["vocab"].get(g)
        if i is not None:
            counts[i] = counts.get(i, 0) + 1
    x = {i: c * m["idf"][i] for i, c in counts.items()}
    nrm = math.sqrt(sum(v * v for v in x.values()))
    if nrm > 0:
        x = {i: v / nrm for i, v in x.items()}
    scores = [sum(v * m["coef"][k][i] for i, v in x.items()) + m["intercept"][k] for k in range(len(m["labels"]))]
    mx = max(scores)
    ex = [math.exp(v - mx) for v in scores]
    tot = sum(ex)
    p = [v / tot for v in ex]
    k = max(range(len(p)), key=lambda j: p[j])
    return m["labels"][k], p[k]


check = Xva + Xte
sk = clf.predict(vec.transform(check))
agree = sum(1 for t, a in zip(check, sk) if predict_from_json(model, t)[0] == a)
print(f"export check: {agree}/{len(check)} predictions identical to scikit-learn")
assert agree >= len(check) * 0.99, "exported model does not match scikit-learn - do not use it"

tests = [{"text": t, "expected": predict_from_json(model, t)[0]} for t in Xte[:80]]
tests_expected_truth = [{"text": t, "expected": a} for t, a in zip(Xte[:80], yte[:80])]
json.dump(tests, open("kori_intent_tests.json", "w", encoding="utf-8"), ensure_ascii=False)   # expected = what the exported model must say on the phone
print("exported kori_intent_model.json, threshold", best, "- tests:", len(tests))
