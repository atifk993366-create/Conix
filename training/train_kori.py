"""
Fine-tune a small open model on the dataset exported from Kori (Settings > Training > Export).
Run on a free GPU (Google Colab T4 or Kaggle). Usage:  python train_kori.py kori_training.jsonl
"""
import json
import random
import sys

import torch
from peft import LoraConfig, get_peft_model
from transformers import AutoModelForCausalLM, AutoTokenizer

BASE = "Qwen/Qwen2.5-1.5B-Instruct"        # use Qwen/Qwen2.5-0.5B-Instruct for a smaller, faster phone model
DATA = sys.argv[1] if len(sys.argv) > 1 else "kori_training.jsonl"
OUT = "kori-merged"
EPOCHS, LR, MAXLEN, BATCH, ACCUM = 3, 2e-4, 512, 2, 8

tok = AutoTokenizer.from_pretrained(BASE)
rows = [json.loads(l) for l in open(DATA, encoding="utf-8") if l.strip()]
random.seed(0)
random.shuffle(rows)

seen, uniq = set(), []
for r in rows:                                   # drop exact duplicates
    m = r["messages"]
    key = (m[1]["content"], m[2]["content"])
    if key not in seen:
        seen.add(key)
        uniq.append(r)
rows = uniq
split = max(1, int(len(rows) * 0.05))
val_rows, train_rows = rows[:split], rows[split:]
print(f"{len(train_rows)} train / {len(val_rows)} held-out examples")


def encode(msgs):
    prompt = tok.apply_chat_template(msgs[:-1], tokenize=False, add_generation_prompt=True)
    p_ids = tok(prompt, add_special_tokens=False)["input_ids"]
    a_ids = tok(msgs[-1]["content"] + tok.eos_token, add_special_tokens=False)["input_ids"]
    ids = (p_ids + a_ids)[:MAXLEN]
    labels = ([-100] * len(p_ids) + a_ids)[:MAXLEN]
    return ids, labels


def batches(rs):
    pad = tok.pad_token_id if tok.pad_token_id is not None else tok.eos_token_id
    for i in range(0, len(rs), BATCH):
        chunk = [encode(r["messages"]) for r in rs[i:i + BATCH]]
        width = max(len(c[0]) for c in chunk)
        ids = torch.tensor([c[0] + [pad] * (width - len(c[0])) for c in chunk])
        lab = torch.tensor([c[1] + [-100] * (width - len(c[1])) for c in chunk])
        att = torch.tensor([[1] * len(c[0]) + [0] * (width - len(c[0])) for c in chunk])
        yield ids.cuda(), lab.cuda(), att.cuda()


model = AutoModelForCausalLM.from_pretrained(BASE, torch_dtype=torch.float16).cuda()
model.gradient_checkpointing_enable()
model.enable_input_require_grads()
model = get_peft_model(model, LoraConfig(
    r=16, lora_alpha=32, lora_dropout=0.05, task_type="CAUSAL_LM",
    target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
))
params = [p for p in model.parameters() if p.requires_grad]
for p in params:
    p.data = p.data.float()
opt = torch.optim.AdamW(params, lr=LR)
scaler = torch.cuda.amp.GradScaler()

for ep in range(EPOCHS):
    random.shuffle(train_rows)
    model.train()
    total, n = 0.0, 0
    for ids, lab, att in batches(train_rows):
        with torch.autocast("cuda", dtype=torch.float16):
            loss = model(input_ids=ids, attention_mask=att, labels=lab).loss / ACCUM
        scaler.scale(loss).backward()
        total += loss.item() * ACCUM
        n += 1
        if n % ACCUM == 0:
            scaler.unscale_(opt)
            torch.nn.utils.clip_grad_norm_(params, 1.0)
            scaler.step(opt)
            scaler.update()
            opt.zero_grad()
    print(f"epoch {ep + 1}: train loss {total / max(n, 1):.4f}")

model.eval()
ok = 0
checked = val_rows[:50]
for r in checked:                                # how often does it pick the right action on unseen examples?
    m = r["messages"]
    prompt = tok.apply_chat_template(m[:-1], tokenize=False, add_generation_prompt=True)
    x = tok(prompt, return_tensors="pt", add_special_tokens=False).to("cuda")
    with torch.no_grad():
        out = model.generate(**x, max_new_tokens=200, do_sample=False)
    text = tok.decode(out[0][x["input_ids"].shape[1]:], skip_special_tokens=True)
    try:
        ok += int(json.loads(text).get("action") == json.loads(m[2]["content"]).get("action"))
    except Exception:
        pass
print(f"action match on {len(checked)} held-out examples: {ok}")

model = model.merge_and_unload()
model.save_pretrained(OUT)
tok.save_pretrained(OUT)
print("saved to", OUT)
