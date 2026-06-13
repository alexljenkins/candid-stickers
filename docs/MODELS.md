# On-device models

## MobileCLIP-S0 (CLIP tagging / text search)

ONNX export of Apple's MobileCLIP-S0, from
[Xenova/mobileclip_s0](https://huggingface.co/Xenova/mobileclip_s0) (fp16
variants, which keep float32 inputs/outputs). Runtime:
`com.microsoft.onnxruntime:onnxruntime-android`.

| Asset | Where | Fetch |
|---|---|---|
| `clip/vision_model_fp16.onnx` (22,876,479 B) | gitignored | `scripts/fetch-models.sh` |
| `clip/text_model_fp16.onnx` (84,971,030 B) | gitignored | `scripts/fetch-models.sh` |
| `clip/tokenizer.json` (2,224,081 B) | committed | vendored |

Run `scripts/fetch-models.sh` after a fresh clone; it downloads the two ONNX
encoders into `app/src/main/assets/clip/` and verifies pinned sha256 hashes.

**License:** Apple Sample Code License (via MobileCLIP). Retain Apple's
copyright/license notice when redistributing — see
`https://github.com/apple/ml-mobileclip/blob/main/LICENSE` and the model card
at `https://huggingface.co/Xenova/mobileclip_s0`.

### Preprocessing contracts (deviation breaks matching silently)

- **Vision** — input `pixel_values` float32 NCHW `[1,3,256,256]`, output
  `image_embeds` `[1,512]`. Resize shortest edge to 256 (BILINEAR),
  center-crop 256x256, RGB order, scale to 0–1 (`x/255`).
  **NO mean/std normalization** (`do_normalize=false`); applying the usual
  CLIP-ViT mean/std silently breaks matching.
- **Text** — input `input_ids` int64 `[1,77]`, output `text_embeds` `[1,512]`.
  Sequence length must be **exactly 77** (fixed positional Add) or it crashes;
  pad with 0 after EOS. No attention_mask input.
- **Tokenizer** — HF `tokenizer.json`, CLIP BPE: vocab 49408, BOS 49406,
  EOS 49407, pad 0, context 77. Lowercase + NFC, GPT-2-style byte-to-unicode
  mapping, merges; each word's final token uses the `</w>`-suffixed form.
  Golden: `"crying laughing"` → `[49406, 6828, 8301, 49407]` + 73 zeros.
- Raw outputs of **both** encoders are NOT unit-norm — L2-normalize before
  cosine similarity. Embedding dim 512.

## MobileFaceNet (face clustering)

TFLite face-recognition embedder (BSD-3/Apache lineage). The raw upstream URL
is not immutable, so the binary is **vendored into the repo** at
`app/src/main/assets/mobilefacenet.tflite`
(5,233,552 B, sha256
`be4bc7cfc53f7bc336d0f28b1ab92535f618c913a422b683210750f6b5354854`).
Runtime: `com.google.ai.edge.litert:litert` (use the
`org.tensorflow.lite.Interpreter` API; do **not** also add
`org.tensorflow:tensorflow-lite`).

### Preprocessing contract

- Input tensor `input` `[1,112,112,3]` float32 NHWC RGB, normalize
  `(x - 127.5) / 127.5`.
- Output tensor `embeddings` `[1,192]`, **already L2-normalized**
  (cosine == dot product).
- **Alignment:** ArcFace 5-point similarity transform (Umeyama, 4-DOF:
  uniform scale + rotation + translation, no reflection) to 112x112, warped
  from the full-resolution photo. Source points come from MediaPipe
  FaceLandmarker 478 landmarks (see `FaceAnalyzer.Keypoints`):
  leftEye=468 (fallback midpoint 33/133), rightEye=473 (fallback midpoint
  362/263), nose=1, mouthLeft=61, mouthRight=291; eye and mouth pairs ordered
  by ascending image x. Template points in 112x112 px:
  LE(38.2946,51.6963) RE(73.5318,51.5014) N(56.0252,71.7366)
  ML(41.5493,92.3655) MR(70.7299,92.2041).
- Discard faces with inter-ocular distance < 40 source px.
- Thresholds for this model: same-person assign cosine >= 0.50,
  cluster-merge >= 0.55, eject < 0.35.
