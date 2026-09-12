# AI Moderator Helper - Fabric 1.21.4 (client mod)

Analyzes player chat with an AI and suggests MUTE-only punishments. Nothing is applied automatically - only after `.m c` (or ✔ in `.m menu`).

## What was fixed in 1.1.0

1. **AI never ran** - the old `chatRegex` required `Nick: text` / `<Nick> text`, but the server sends
   `PLAYER ywthshfejsgec > text`, `HELPER ~Djast SUPPORT > Crypa228 text` (arrow separator, rank words,
   tilde nicks, Cyrillic titles). No line ever matched, so no request was ever sent.
   New `ChatParser` (mode `auto`) understands arrows, colons, ranks, `[VIP]` prefixes and `~Nick`.
2. **Messages were dropped** by the rate limiter - now they are queued (`maxQueueSize`).
3. **Silent failures** - missing API key, HTTP 401/402/404/429, non-JSON answers are now reported in chat.
4. **Possible deadlock** - blocking HTTP sends ran on the HttpClient's own executor; now a dedicated pool.
5. **Model answer parsing** - markdown fences, array content, Anthropic/Responses formats, string booleans.
6. `temperature` is dropped automatically if the model rejects it.
7. Duration fallback from the rules file when the model returns only a rule number.
8. Old broken configs are migrated automatically on startup.

## Commands

| Command | Action |
|---------|--------|
| `.m c` | Confirm the found violation -> `/tempmute nick time rule` |
| `.m d` | Decline the violation: nothing is sent, the on-screen card disappears |
| `.m menu` | Open the mute menu: nick / message / rule / reason, duration switch, buttons ✔ and ✘ |
| `.m config` | Settings menu |
| `.m on` / `.m off` | Enable/disable chat analysis |
| `.m logs on` / `.m logs off` | Mirror AI logs into chat (`.m logs` toggles) |
| `.m test <text>` | Send a test message to the AI and show the raw answer |
| `.m status` | Show analysis/logs/key/model/queue state |
| `.m clear` | Drop the current suggestion |

## Debugging with `.m logs on`

Logs show every stage, so you can see exactly where it breaks:

- `В очередь: Nick — text` - message accepted for analysis
- `Запрос к AI: <url> (model=..., nick=...)` - request sent
- `Ответ AI: {...}` - raw model verdict
- `ИИ не ответил: HTTP 401: неверный API Key` - key/endpoint/model problem
- `Нарушений нет: Nick — text` - AI answered, no violation

## Setup (PojavLauncher)

1. Fabric Loader for 1.21.4.
2. Put in `.minecraft/mods/`: `fabric-api-0.114.0+1.21.4.jar` and `ai-moderator-1.2.0.jar`.
3. In game: `.m config` -> enter API Endpoint, API Key, Model -> turn analysis ON.
4. Check with `.m test ты лошок` - the AI must answer with JSON.
5. Turn on `.m logs on` while testing on a live server.

## Build

JDK 21 + internet:

```bash
gradle wrapper --gradle-version 8.10
./gradlew build
```

Output: `build/libs/ai-moderator-1.2.0.jar` (or use the GitHub Actions workflow).

## Config (`config/aimoderator/config.json`)

- `apiEndpoint`, `apiKey`, `model` - any OpenAI-compatible API (OpenAI, OpenRouter, local proxy). `/v1/chat/completions` is appended automatically.
- `chatAnalysisEnabled`, `logsEnabled`
- `chatParseMode`: `auto` (smart parser) or `regex` (use `chatRegex` only, group 1 = nick, group 2 = message)
- `chatRegex` - optional custom regex, empty by default
- `minRequestIntervalSeconds`, `maxConcurrentRequests`, `maxQueueSize`, `maxMessageLength`, `requestTimeoutSeconds`
- `muteCommandTemplate` - default `tempmute {nick} {duration} {rule}`; placeholders `{nick} {duration} {rule} {reason}`
- `showHudNotification`, `showChatNotification`, `hudSeconds`

Rules: `config/aimoderator/rules.txt` (editable in the menu). Only chat rules punished with MUTE are included.
