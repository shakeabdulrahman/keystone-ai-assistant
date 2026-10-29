# Demo script: 3 minutes

For a screen recording (Android Studio emulator + Loom or OBS, both free) or a live interview. Practise twice and keep each beat short. Times are cumulative.

**Before you start:** cloud backend warmed up (open `https://<your-service>.onrender.com/healthz` a minute before; the free server sleeps), or the local backend running, signed in as the demo user, notifications allowed, emulator microphone on (Extended controls → Microphone → "Virtual microphone uses host audio input"), Grafana open in a browser tab, one older conversation in history.

| Time | Show | Say |
| --- | --- | --- |
| 0:00–0:15 | Home screen | "This is Keystone, an AI HR assistant I built end to end: a Kotlin Multiplatform Android app and a Python AI gateway. The home screen already knows what needs my attention: an overdue task, pending leave and my balances, ranked by the server." |
| 0:15–0:40 | Tap the overdue task → chat asks about it; then type "How many leave days do I have left?" | "Tapping an item asks the assistant for me. Answers stream token by token. When it needs data, the model calls a tool on the server, and the app renders a native card, not text. The phone never sees an API key." |
| 0:40–1:05 | "Can I carry over unused leave?" → tap citation [1] | "Policy questions use RAG: hybrid vector and keyword search over company documents. Citations are verified on the server, so the model can't invent a source. Tapping one opens the passage." |
| 1:05–1:40 | "Book annual leave on Monday and Tuesday after next" → card → **Submit** → ✓ LR-1050 | "Now it acts, but only with my confirmation. The model can propose, never execute. The card shows exactly what will run: two working days, balance 14 to 12. My tap calls a confirm endpoint that runs exactly once, even if I double-tap." |
| 1:40–2:05 | Press Home on the emulator; wait for the notification (≈20 s) | "A simulated manager approves it. The push says only 'your leave request has an update': no HR data on the lock screen. The details load in the app." *(Fill the wait with the next beat if needed.)* |
| 2:05–2:30 | Tap the mic: "What's on my calendar this week?" | "Voice uses Android's on-device recognizer, so audio never leaves the phone. The answer is spoken sentence by sentence while it streams. I can interrupt it by tapping the mic." |
| 2:30–2:50 | Grafana dashboard | "It's built like production: per-user and per-IP rate limits, Firebase App Check, security headers, and metrics like time to first token and tool latency, without logging what anyone asked." |
| 2:50–3:00 | README architecture diagram | "Backend, app and docs are on GitHub with 350+ tests and ADRs for every major decision. It's deployed free on Render and Neon, with chat on Groq and automatic failover to Gemini, so one AI vendor going down doesn't take the assistant down. Happy to go deeper on any part." |

## If something goes wrong live

- **Cold start (cloud):** "Free tier, it's waking up". Switch to the local backend.
- **Model is slow or odd:** set `LLM_PROVIDER=fake`. The tool cards, citations and actions still demo the architecture.
- **Booked leave disappeared:** the demo HR system keeps changes in memory, and the free server forgets them when it sleeps. Book again, or record in one session.
- **No push:** show the backend log line `push_logged` and the in-app notification.

## Recording tips

- 1080p, emulator at 100% zoom, light theme, a large font size for readability.
- Hide the emulator toolbar and turn off system notifications other than Keystone.
- Add subtitles. Many reviewers watch muted.
