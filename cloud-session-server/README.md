# EricPDF OpenRouter transcription proxy

The Android app **does not** need an OpenAI API account or OpenAI API key.

EricPDF records the full WAV locally and, when **Online** is selected, sends
~6-second audio chunks through this HTTPS proxy. The server sends the WAV
to OpenRouter's `/api/v1/audio/transcriptions` using your private OpenRouter key,
returning a text segment. This is **near-live** transcription (~6 seconds plus
network processing), not continuous per-word streaming.

## Deploy on Render

1. Create your OpenRouter API key: https://openrouter.ai/settings/keys
2. Deploy GitHub `Eric9435/Ericpdf`, branch `main`, as a Node web service.
   Set **root directory** `cloud-session-server`, build command `npm install`,
   start command `npm start`, region **Frankfurt**.
3. Set **private environment variables** in Render:
   - `OPENROUTER_API_KEY` — your OpenRouter key
   - `ERICPDF_ACCESS_TOKEN` — a random string of at least 24 characters
   - `OPENROUTER_STT_MODEL` — optional, defaults to `openai/whisper-large-v3-turbo`
   - `ERICPDF_MAX_DAILY_AUDIO_SECONDS` — optional, default 7200
4. Deploy. Check `https://YOUR-SERVICE.onrender.com/health`.
5. On your phone: EricPDF → **Online** → set URL
   `https://YOUR-SERVICE.onrender.com/transcribe`
   and enter the same `ERICPDF_ACCESS_TOKEN`.
6. Import PDF; obtain professor's recording permission; tap Record.

### Security and costs

- Keep OpenRouter keys and server access codes **out of GitHub** and chat.
- Render does not persist audio/transcript in this proxy implementation,
  but OpenRouter and the selected upstream provider process each submitted clip.
- Private local data (PDFs, full WAV, ink, notes) remains on your device.
- Cloud recognition is opt-in and can incur API charges, independently of ChatGPT.
- Request rate limiting and daily audio usage limits are memory-only, so
  they reset on server restart. Also configure OpenRouter billing limits.
- An Internet outage does not prevent the local WAV recording from continuing.
- Word cuts between audio chunks may reduce transcription accuracy.

See OpenRouter's own docs:
https://openrouter.ai/blog/tutorials/transcription-on-openrouter/
https://openrouter.ai/openai/whisper-large-v3-turbo/api
