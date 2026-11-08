# Training Kori's own model

Kori saves your commands and its answers (Settings > Training). A small open model can learn the
"what you say -> which action" part very well. Open-ended general knowledge stays much weaker than
Gemini, so Kori keeps using web retrieval (Wikipedia) and the free providers for hard questions.

1. In Kori: Settings > Training > **Export to Downloads**. Collect at least 500-1000 examples first
   (the more varied, the better; examples from the instant parser are free and always correct).
2. Open Google Colab, choose a **T4 GPU** runtime, upload `kori_training_*.jsonl` and `train_kori.py`.
3. Run:
   ```
   !pip install -q transformers peft accelerate
   !python train_kori.py kori_training_XXXX.jsonl
   ```
   It prints the loss per epoch and how often the predicted action matches on unseen examples.
4. Convert to a phone-friendly file (GGUF) with llama.cpp:
   ```
   !git clone https://github.com/ggml-org/llama.cpp
   !pip install -q -r llama.cpp/requirements.txt
   !python llama.cpp/convert_hf_to_gguf.py kori-merged --outfile kori.gguf --outtype q8_0
   ```
   (Check the llama.cpp README if a flag has changed. For a smaller 4-bit file, build `llama-quantize`.)
5. Download `kori.gguf`. Running it inside the app is the next build step (llama.cpp runtime);
   send the file size and the "action match" number and it can be wired in.
6. When you are happy, use Settings > Training > **Delete all** to remove the saved examples.

Privacy: the dataset contains what you typed or said to Kori (names, messages you dictated).
Only upload it to your own Colab / Drive.
