# Candid Stickers

Turn your camera roll's throwaway photos into your group chat's best stickers.

An on-device mobile app that scans your photo library for candid faces — eyes closed, mid-sneeze, dead stares, gremlin mode — auto-crops them into transparent stickers, groups them by person, and makes them semantically searchable ("crying laughing", "disgusted face") so you can drop them into WhatsApp, Telegram, iMessage and anywhere else stickers go.

Everything runs on the phone: face detection, subject matting, expression scoring, identity clustering, and CLIP-based semantic search. No server, no upload, no account.

## Status

Early development. Design docs live in the [idea-validation](https://github.com/alexljenkins/idea-validation) repo under `ideas/face-meme-stickers/`.

## Planned stack (Android first)

- Kotlin, native
- MediaPipe Face Detection + Face Landmarker (blendshape-based "meme-ability" scoring)
- ML Kit Subject Segmentation (auto-matte crops)
- MobileCLIP embeddings + `sqlite-vec` (semantic search)
- MobileFaceNet-class embeddings + cosine clustering (who-is-who grouping)
- WhatsApp third-party sticker pack API / Telegram `importStickers` / share-sheet export

## License

[PolyForm Small Business License 1.0.0](LICENSE.md) — free to use for:

- **Personal / noncommercial use** — hobby, study, research, private entertainment.
- **Noncommercial organizations** — charities, schools, public institutions.
- **Small businesses** — fewer than 100 employees/contractors and under US$1,000,000 (2019 dollars, inflation-adjusted) revenue in the prior tax year.

Larger commercial use requires a separate license — open an issue or contact the author.
