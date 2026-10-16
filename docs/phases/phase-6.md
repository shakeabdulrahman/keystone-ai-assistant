# Phase 6 — Voice

**Outcome:** you can talk to Keystone. Tap the mic and speak: your words appear live in the input box, and when you stop, the question is sent. The answer is read aloud while it is still streaming, starting after its first sentence, not when it's complete. Tap the mic again while it talks to interrupt and ask something else. A speaker button reads typed answers aloud too. Everything uses Android's own speech services, so it's free, and audio never reaches our server.

## 1. What we built

| Area | What exists now | Key files |
| --- | --- | --- |
| Voice-aware answers | `input_mode: "voice"` in `POST /v1/chat`; the system prompt gains spoken-answer rules (short sentences, no lists, numbers said naturally, "tap the card" for actions); logged per turn | `backend/app/api/chat.py`, `app/ai/prompts/voice.md` |
| Sentence streaming (KMP) | `SentenceChunker` splits the streaming answer into sentences (skips "e.g.", "2.5", joins very short ones) | `shared/.../voice/SentenceChunker.kt` |
| Speech-friendly text (KMP) | `speakable()`: removes [1] markers and Markdown, says "→" as "to", "&" as "and" | `shared/.../voice/Speakable.kt` |
| Speech to text | `SpeechInput` interface; `AndroidSpeechInput` over `SpeechRecognizer` (on-device on Android 12+ when available, falls back if the language isn't installed, prefers offline), partial results, cold Flow that releases the mic on cancel | `androidApp/.../voice/` |
| Text to speech | `SpeechOutput` interface; `AndroidSpeechOutput` over `TextToSpeech` (queues sentences until the engine is ready, tracks "speaking", no auto-speech while TalkBack is on) | `androidApp/.../voice/AndroidSpeechOutput.kt` |
| Chat | Mic button (pulses while listening), live transcript, barge-in, read-aloud toggle, voice errors, permission asked on first tap; spoken questions' retries stay spoken | `ChatViewModel.kt`, `ChatScreen.kt`, `VoiceIcons.kt` |
| Manifest | `RECORD_AUDIO`; `<queries>` for the speech services (Android 11+ package visibility) | `AndroidManifest.xml` |
| Tests | Backend 172 with Postgres (+1: voice prompt rules, invalid mode → 422). Mobile: chunker (5), speakable (3), voice request body (1), ViewModel voice flows (9) | `backend/tests/test_chat_api.py`, `mobile/**/test` |

## 2. How it works

```
Tap mic ─▶ permission? ─▶ SpeechRecognizer (on-device if possible)
              │ partial: "how many leave"     → shown in the input box
              │ final:   "How many leave days do I have"
              ▼
ChatViewModel.send(text, InputMode.Voice) ─▶ POST /v1/chat {…, "input_mode":"voice"}
                                              └▶ system prompt + voice rules
SSE text.delta "You have 14 days " "of annual leave [1]. " "They expire on…"
   └▶ SentenceChunker ── "You have 14 days of annual leave [1]." ─▶ speakable() ─▶ TextToSpeech (queue)
                       └ "They expire on 31 March."              ─▶ … spoken as soon as complete
Tap mic while speaking ─▶ TTS stop + answer stop + listen again (barge-in)
```

- **Time to first word.** Waiting for a full answer before speaking adds seconds. Sentence chunking starts speech as soon as the first sentence exists, the voice equivalent of time to first token.
- **Hard parts of sentence splitting:** a dot isn't always the end of a sentence (e.g., 2.5 days, Dr.), and text arrives in random pieces. The chunker only cuts at punctuation followed by whitespace, skips abbreviations, and gives the same result however the text is sliced (tested).
- **Privacy.** The transcript is the only thing sent, the same as typing. On Android 12+ recognition can run fully on the phone.
- **Package visibility (a classic Android gotcha).** From Android 11, apps can't see other apps' services unless they declare `<queries>`. Without them, `SpeechRecognizer.isRecognitionAvailable` returns false and TTS can't bind. The mic would vanish with no error.
- **Accessibility.** With TalkBack on, answers aren't read aloud automatically, because two voices at once are unusable. Errors and the mic state are announced.
- **Safety unchanged.** Asking by voice can prepare a leave request, but confirming is still a tap. The voice prompt tells the model to say so.

## 3. Why we chose it

| Decision | Why | Alternative rejected |
| --- | --- | --- |
| Platform STT/TTS | Free, private, offline-capable, low latency | Cloud speech APIs (cost, audio leaves the device) |
| Transcript-only to backend | Same pipeline as text; no audio storage or processing | Uploading audio for server-side Whisper |
| Sentence-level streaming TTS | Speech starts early, natural pauses | Speak after the full answer (slow); word-level (choppy) |
| Chunker and cleanup in KMP shared | iOS reuses the exact behaviour; pure functions, easy tests | Android-only helpers |
| Interfaces + fakes for speech | ViewModel logic tested on the JVM, no device | Instrumented tests only |
| Tell the backend it's voice | Answers designed for listening (short, no tables) | Post-processing long answers on the phone |
| Tap to confirm, even by voice | A spoken "yes" is ambiguous and spoofable (ADR 0011) | Voice confirmation |

## 4. Bugs and lessons from this phase

1. **A race in the test fake.** The ViewModel subscribed to the microphone inside a coroutine that hadn't started yet, so speech events sent straight after tapping the mic were lost. The review caught three failing tests and one that passed without testing anything. Fix: subscribe synchronously (the flow is cold, so the mic still opens only when collected). The lesson: "fire and forget" launches create windows where events vanish.
2. **On-device recognition without the language pack.** Android 12+ can report on-device recognition as available while lacking the user's language, so it fails every time. After such an error the app falls back to the standard recognizer.
3. **No icon library bloat.** Mic and speaker icons aren't in Material's core set. Instead of adding the very large extended icon library, three vector paths are drawn directly.

## 5. How to run and test it

1. **Backend:** `docker compose up --build` (no migration this time).
2. **Emulator microphone:** in the running emulator, open **Extended controls (⋯) → Microphone** and turn on **Virtual microphone uses host audio input**. Allow microphone access for the emulator on your Mac if macOS asks. A Google Play system image is needed for speech recognition.
3. **Android Studio:** sync and run.
4. **Try it:**
   - Tap the mic and allow the permission. Say "How many leave days do I have left?" Watch the live transcript; the answer is spoken in short sentences.
   - Ask a policy question by voice: the [1] markers are shown but not spoken.
   - While it's speaking, tap the mic: it stops and listens again (barge-in).
   - Type a question: it stays silent. Tap the speaker icon (top right) and ask again: now it reads aloud.
   - Say "Book annual leave on Monday and Tuesday after next": you hear that it's prepared, and you still tap the card to confirm.
   - Deny the permission: a clear message appears. Say nothing: "Didn't catch that".
   - Turn on TalkBack: answers are no longer read aloud automatically.
5. **Physical phone (better microphone):** the same `adb reverse` setup as before.
6. **Tests:** `cd backend && uv run pytest` · `cd mobile && ./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest`

## 6. Interview concepts demonstrated

- Voice UX: streaming speech, time to first word, barge-in, partial transcripts
- Mobile speech APIs: SpeechRecognizer (on-device vs standard), TextToSpeech lifecycle and queuing
- Callback APIs as cold Flows (`callbackFlow`, `awaitClose` releasing resources)
- Android 11+ package visibility (`<queries>`), runtime permissions with the Activity Result API
- Privacy by design: on-device processing, transcript-only, no audio stored
- Accessibility: TalkBack coexistence, live regions, content descriptions
- Text normalisation for speech; robust incremental sentence segmentation
- Testable architecture: platform services behind interfaces, fakes, virtual time
- Multiplatform reuse of pure logic (KMP) for future iOS parity

**60-second explanation:**

> "Voice uses Android's own speech services: SpeechRecognizer for speech-to-text, preferring the on-device recognizer so audio stays on the phone, and TextToSpeech for answers. Only the transcript goes to the backend, flagged as voice, so the model answers in short spoken sentences. The answer is spoken while it streams: a shared Kotlin Multiplatform sentence chunker turns text deltas into complete sentences, handling abbreviations and decimals, and a speakable() function removes citation markers and Markdown. Speech starts after the first sentence instead of the full answer. Tapping the mic interrupts speech and the stream, and with TalkBack on we don't auto-speak. The recognizer is wrapped in a callbackFlow so cancelling always releases the microphone, and the platform services sit behind interfaces, so all of this is unit-tested with fakes and virtual time."

## 7. Likely interview questions

1. **How do you make a voice assistant feel fast?** Stream everything: the transcript while speaking, the answer from the server, and speech per sentence as soon as it's complete. Measure time to first word, not total time.
2. **Why on-device speech recognition?** Privacy (HR questions), no cost, works offline, and lower latency. The trade-offs are quality and language coverage per device, so fall back to the standard recognizer.
3. **What is barge-in and how did you implement it?** The user interrupts the assistant by talking. Tapping the mic stops TTS, cancels the streaming request (which also stops generation on the server, as in Phase 1), and starts listening.
4. **Why does the mic button disappear on some Android 11+ devices, and how do you fix it?** Package visibility. Declare `<queries>` for `RecognitionService` and `TTS_SERVICE`.
5. **How do you test speech features without a device?** Put the platform APIs behind interfaces, inject fakes that emit scripted events, and drive time with the coroutine test scheduler. Keep the hard logic (chunking, cleanup) in pure functions.
6. **Should a voice "yes" confirm a leave request?** No. Speech can be misheard, overheard or replayed. Confirmation stays an explicit tap tied to the action ID.

## 8. Check yourself

1. Why does the app tell the backend a question was spoken, instead of shortening answers on the phone?
2. "Leave is 2.5 days. Ask your manager." arrives in chunks "Leave is 2.", "5 days. As", "k your manager." What does the chunker release, and when?
3. What does `speakable("You can carry over 5 days [1].")` return?
4. What happens, step by step, when you tap the mic while an answer is being spoken?
5. Without `<queries>` in the manifest, what would the user see on Android 11+?

## 9. CV / LinkedIn line

> Added private, low-latency voice to a Kotlin Multiplatform AI assistant: on-device Android speech recognition with live transcripts, sentence-level streaming text-to-speech from a shared KMP chunker, barge-in, accessibility-aware auto-speech and voice-optimised LLM answers, all unit-tested through injected speech interfaces.

## Next: Phase 7 — Home dashboard and push notifications

A home screen that shows what needs attention (pending approvals, overdue tasks, upcoming meetings, leave balance) without asking, plus push notifications through Firebase Cloud Messaging when an HR request changes or a leave request is decided.
