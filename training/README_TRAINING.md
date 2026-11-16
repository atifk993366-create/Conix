# Training Kori's own models

Two small, separate pieces. Do the first one first: it is far more achievable on a free Colab than a chat model.

## A. Intent classifier (start here) - runs inside the app, fully offline
It reads a short command and says which action it is (timer, alarm, flashlight, open_app ...). Kori already ships a
baseline trained on `seed_intents.csv` (about 800 hand-written commands in English, Hinglish, Hindi and Odia). Improve it with your own data:

1. In Kori: Settings > Training > **Export intent CSV**. It labels every command you gave with the action Kori ran
   and masks phone numbers and e-mail addresses. Open the CSV and fix wrong labels.
2. Colab (CPU is enough). Upload `01_prepare_dataset.py`, `02_train_intent_classifier.py`, `seed_intents.csv` and your CSV, then:
   ```
   !python 01_prepare_dataset.py seed_intents.csv kori_intents_XXXX.csv
   !python 02_train_intent_classifier.py
   ```
   Step 1 cleans, de-duplicates and splits 80/10/10 so near-identical sentences stay in one split.
   Step 2 prints accuracy per class, a confusion matrix and the mistakes, exports the model, and **checks that the exported file
   gives the same answers as scikit-learn** before you use it. Do not judge quality from training accuracy; look at the held-out test.
3. Download `kori_intent_model.json` and `kori_intent_tests.json`. In Kori: Diagnostics > Import model or test file
   (import both), then **Run self-test**. It re-runs the fixed sentences on the phone and reports matches and milliseconds.
4. Kori only acts on a prediction when its confidence is above the exported threshold, and only for safe intents
   (timer, alarm, flashlight, volume, music, battery/network, open app, modes). Everything else still goes to the AI brain.
5. Keep a record of the dataset version and the evaluation report next to the model. The model file lists its version and dataset size.

## B. Assistant language model (later, optional)
`train_kori.py` fine-tunes a small open model (LoRA) on the chat-format file from Settings > Training > Export to Downloads.
Collect at least 1000 varied examples first. Converting the result to GGUF and running it inside the app is a separate step
(llama.cpp runtime); measure RAM, latency and battery on your phone before committing to it.

Privacy: both datasets contain what you said to Kori. Upload them only to your own Colab / Drive, and delete the saved examples
(Memory & Privacy > Delete training data) when you are done. Do not train on other people's private calls.
