# ADR 0012: Voice with the platform's own speech services, streamed sentence by sentence

- Status: Accepted
- Date: 2026-10-16

## Context
Employees want to ask questions hands-free, and answers should be audible. The project has no budget for paid speech APIs, and HR questions are personal.

## Decision
- **Speech to text** uses Android `SpeechRecognizer`. On Android 12+ it uses the on-device recognizer when one is available, falling back to the standard one if the language isn't installed. It always prefers offline recognition.
- **Text to speech** uses Android `TextToSpeech`, with on-device voices.
- **No audio reaches our backend.** Only the transcript is sent, together with `input_mode: "voice"`, so the model answers in short spoken sentences.
- **Answers are spoken sentence by sentence while they stream.** A shared KMP `SentenceChunker` splits the stream, and `speakable()` strips citations and Markdown before speaking.
- **Barge-in:** tapping the mic stops speech and any answer in progress.
- **Confirmation stays a tap.** A spoken "yes" never confirms an action (ADR 0011).
- **No automatic speech while TalkBack is on**, so the two voices don't overlap.

## Consequences
- Free, private and low latency, with nothing extra to deploy.
- Recognition quality and offline support depend on the device and its language packs. Emulators need host-microphone input enabled.
- iOS will use `SFSpeechRecognizer` and `AVSpeechSynthesizer` behind the same idea, reusing the shared chunker.

## Alternatives considered
- **Cloud STT/TTS (Google Cloud Speech, Whisper API, ElevenLabs):** better voices, but it costs money and sends audio off the device.
- **Running Whisper on the backend:** audio would leave the phone, and it needs a GPU to feel responsive.
- **Realtime voice models over WebSocket:** impressive, but paid and a much bigger change. Revisit later.
- **Speaking only after the full answer:** simpler, but feels slow.
