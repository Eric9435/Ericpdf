# EricPDF — Optional online speech server

The app's **Online** option requires this small authentication service. It exchanges the
app's private access code for a short-lived OpenAI Realtime transcription credential.
The Android APK never contains your permanent OpenAI API key.

## Deploy

1. Create an API project and secret key in your OpenAI API dashboard (this is
   **separate from ChatGPT Plus**; speech API usage is billable).
2. Deploy this `cloud-session-server` folder as a Node 20+ Web Service on Render
   or an equivalent host. On Render set **Root directory** to
   `cloud-session-server` and **Start command** to `npm start`.
3. Under your hosting service's private Environment settings add:
   `OPENAI_API_KEY` = your API key, and
   `ERICPDF_ACCESS_TOKEN` = a random private string at least 24 characters long.
   NEVER commit either value to GitHub or paste them into this chat.
4. Start the service. Check `https://YOUR-SERVICE.onrender.com/health` shows `{"ok":true}`.
5. In EricPDF → Library choose **Online**, enter the HTTPS address
   `https://YOUR-SERVICE.onrender.com/session` and enter the same
   `ERICPDF_ACCESS_TOKEN` access code.
6. Set English or German as appropriate, import a PDF, obtain recording permission
   from your professor, then tap **Record**.

### Privacy and cost
- PDFs, bookmarks, handwritten notes, transcripts and WAV recordings remain local.
- During online mode **live audio is transmitted to OpenAI** for transcription,
  subject to the provider's own data processing terms.
- No live audio or transcript is stored by this token-issuing service.
- Your main API key exists **only** in the server environment.
- The server requires an access code, limits requests per IP and issues 60-second tokens.
  This sample is **single-user** infrastructure, not hardened multi-tenant production
  authentication. Add persistent abuse quotas and auth if publishing a multi-user app.
- Select **Offline** to use on-device Vosk without network requests.
- If cloud transcription fails, EricPDF still writes the raw lecture WAV;
  speech in the disconnected interval cannot appear in live notes.
- Provider costs and hosting costs depend on usage. Set an API project budget.
- Live captions may be inaccurate, especially for equations and technical terminology.
- On Android, the foreground microphone service normally works after app backgrounding
  but can be interrupted by OS power management, microphone conflicts or user settings.

Live transcription docs:
https://developers.openai.com/api/docs/guides/realtime-transcription

Ephemeral token docs:
https://developers.openai.com/api/reference/resources/realtime/subresources/client_secrets/methods/create
