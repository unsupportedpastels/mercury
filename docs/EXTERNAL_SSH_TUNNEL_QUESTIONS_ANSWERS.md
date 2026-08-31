# External SSH tunnel: рішення та відповіді для продовження

- **Статус документа:** рішення для реалізації
- **Дата перевірки:** 2026-08-29
- **Android branch:** `docs/external-ssh-tunnel-session-auth`
- **Android checkpoint:** `ad7a581a823cd8f3c4a5580d9ebf71b88906660d`
- **Перевірений Hermes upstream:** `NousResearch/hermes-agent@4209d371aa1bb8840ce8447555bdd863a1a96c38`, version `0.20.6` (`2026.8.27`)
- **Реальний protocol smoke test:** Hermes `v0.20.4` (`2026.8.18`) на `127.0.0.1:9119`

Цей документ є самодостатнім handoff для Cursor і **має пріоритет** над суперечливими формулюваннями в `docs/CURSOR_CONTINUATION.md`. Найважливіше уточнення: для REST **лише `401` означає відхилення облікових даних**. `403` не можна автоматично перетворювати на повторне отримання токена.

## Короткі обов’язкові рішення

- Спочатку завершити correctness поточного Task 4A; не будувати lifecycle/UI поверх неправильної auth-семантики.
- REST `401` → credential rejection. REST `403` → авторизація пройшла, але операцію заборонено; не refresh і не replay.
- Повторювати автоматично можна тільки явно перелічені idempotent reads. Будь-які mutations, transcription/TTS і controller input — лише ручний повтор.
- Одночасні `401` для одного відхиленого loopback credential мають спільно виконати один bootstrap; кожне читання повторюється максимум один раз.
- Перший випуск підтримує лише числові loopback hosts: `127.0.0.1` і `[::1]`; `localhost` не приймається в tunnel mode.
- Функція виходить як **Experimental** і вважається завершеною лише після фізичного Android E2E.

---

## 1. Обсяг і дорожня карта

### 1. Який повний список етапів?

Попередні документи не мали канонічної наскрізної нумерації. Відтепер використовувати таку:

1. **Task 1 — connection mode і persisted metadata.** `Direct` / `ExternalSshTunnel`, loopback validation, migration.
2. **Task 2 — credential/bootstrap/REST foundation.** `HermesCredential`, HTML bootstrap, memory-only loopback token, typed REST credentials.
3. **Task 3 — WebSocket credential routing.** OAuth `?ticket=`, loopback `?token=`, `None` без auth query; chat і speech parity.
4. **Task 4A — REST recovery correctness.** `401` taxonomy, shared one-bootstrap retry для idempotent reads, no mutation replay, generation/profile guards.
5. **Task 4B — WebSocket/speech recovery.** `4401` refresh, bounded reconnect/reconciliation, без prompt/controller replay і без закриття shared runtime.
6. **Task 5 — lifecycle recovery reducer.** Foreground/background, tunnel loss, backoff, network hints, active-turn deadline, cached/offline state.
7. **Task 6 — endpoint and transport hardening.** Wrong-service detection, non-cryptographic `install_id` warning, origin isolation, release cleartext policy.
8. **Task 7 — product UI і user documentation.** Setup mode, Test tunnel, Experimental label, warnings, recovery copy, README/requirements/testing docs.
9. **Task 8 — release verification.** Full gates, screenshot review, physical Android + external SSH app E2E matrix, PR acceptance review.

### 2. Що завершено?

- **Task 1 — завершено і прийнято:** commit `e2184b8`; focused/full tests і два review пройдені.
- **Task 2 — завершено як foundation:** commit `a1b7567`; typed credentials і bootstrap реалізовані, секрет не зберігається.
- **Task 3 — завершено як credential routing:** commit `8b75fbc`; WebSocket URL routing має тести.
- **Task 4A — розпочато, але не прийнято:** checkpoint `ad7a581`; build зелений, проте strict review знайшов blocking semantic gaps. Окремо цей документ виправляє помилкове припущення review про `403`.
- **Tasks 4B–8 — не завершені.** Частини lifecycle/error types уже з’явилися в checkpoint, але це не означає приймання відповідного етапу.

### 3. Чи входять чотири відкладені теми до всієї функції?

Так. Вони не входили лише до вузького continuation Task 4A, але входять до acceptance всієї feature:

1. WS/speech recovery → Task 4B.
2. Lifecycle reducer/backoff → Task 5.
3. Wrong-service/identity/cleartext → Task 6.
4. Settings UI/docs → Task 7.

Порядок: **4A → 4B → короткий вертикальний smoke → 5 → 6 → 7 → 8**.

### 4. Що пріоритетніше?

Пріоритет — **довести Task 4A до correctness**, потім Task 4B, і лише тоді робити наскрізний smoke. Не приймати «працює в happy path» із відомим ризиком неправильного replay або stale state. Дедлайн не заданий; quality/security важливіші за швидкий неповний release.

---

## 2. Що означає «Task 4A завершено»

### 5. Дефекти A–E вичерпні?

Ні. Це findings одного strict review, а не математично повний список. Cursor має:

- виправити перелічені класи;
- зробити inventory всіх protected REST methods і всіх ViewModel call sites;
- шукати siblings з тією самою помилкою;
- додати table-driven tests за endpoint families.

Водночас старий пункт «усі `401/403` → credential rejection» **скасовано**. Правильно: лише `401`.

### 6. Хто і як робить незалежне review?

Після зелених tests implementation author передає diff іншому контексту: окремому review-agent або human reviewer, який не писав зміни. Формат:

- `PASS` або `FAIL`;
- mapping кожного acceptance criterion до file/line/test evidence;
- окремо security review: secret leakage, origin confusion, replay, concurrency, stale generation;
- reviewer не редагує код під час review.

Це **blocking gate** для оголошення Task 4A завершеним. Для цього проєкту Hermes може виконати окремі spec і security reviews після Cursor implementation.

### 7. Чи можна комітити частинами?

Так, і це бажано. Робити окремі commits за логічними групами, наприклад:

1. REST rejection taxonomy + endpoint tests;
2. retryable-read inventory + shared bootstrap concurrency;
3. mutation/stale guards + OAuth regression;
4. final integration cleanup.

Кожен commit має бути buildable/green. Етап приймається тільки після integrated gates і незалежного review всієї серії.

---

## 3. Нерозв’язані design decisions

### 8. `localhost` чи числові адреси?

**Рішення першого випуску:** tunnel mode приймає тільки `127.0.0.1` і `[::1]`. `localhost` відхиляється з поясненням про IPv4/IPv6 ambiguity. Default — `http://127.0.0.1:9119`.

`Direct` mode не треба змінювати цим рішенням. Підтримку `localhost` можна додати пізніше після device matrix.

### 9. Чи запам’ятовувати `install_id`?

Так, але лише як **non-cryptographic continuity warning**:

- зберігати попередній `install_id` поруч із non-secret catalog metadata;
- при зміні очистити memory credential і показати: «Цей локальний порт тепер схожий на іншу інсталяцію Hermes»;
- вимагати явного Accept new server / Cancel;
- якщо поле відсутнє, працювати через protocol validation без warning;
- ніколи не називати це identity verification або захистом від malicious local service.

### 10. Скільки retry при зникненні тунелю під час active turn?

Не нескінченно. Використати backoff `1s, 2s, 5s, 10s, 30s`, далі максимум кожні `30s`, але загальний automatic recovery budget — **5 хвилин** для active turn. Після цього:

- не replay prompt/controller input;
- зберегти cached/in-flight presentation;
- перейти в `WaitingForTunnel`/manual recovery;
- показати Retry та інструкцію відновити tunnel.

### 11. Назва режиму

Лишається **External SSH tunnel**. Не використовувати абстрактне `Local relay`, бо воно приховує trust boundary. Якщо з’являться інші relay transports, вони отримають окремі explicit modes.

---

## 4. Семантика поведінки

### 12. Чи трактувати `403` як `401`?

**Ні. Це критичне рішення.**

Current Hermes auth middleware повертає `401 {"detail":"Unauthorized"}` для відсутнього або неправильного dashboard token. У тому ж server source багато valid-auth operations повертають `403` через policy/permissions: unreadable files, path outside managed/media roots, sensitive paths, unwritable directories, Host/Origin restrictions.

Отже:

- `401` з credential-bearing protected REST request → credential rejected; loopback read може один раз rebootstrap/retry.
- `403` → operation forbidden; не invalidate token, не bootstrap, не auto-retry.
- WS `4401` → credential problem.
- WS `4403` → Host/Origin/request boundary; не credential refresh.

Cursor має виправити checkpoint classifier, який зараз включає `403`.

### 13. Які операції можна повторювати?

Використовувати explicit allowlist, а не правило «будь-який GET».

**Retryable idempotent reads:**

- status/health discovery;
- session/profile lists і search;
- transcript/history reload;
- model/config/reasoning/voice capability/config reads;
- cron history/runs reads;
- file/directory listing;
- file/image read/download/stream;
- protected authorization-proof session listing.

**Не replay автоматично:**

- create/update/delete session;
- config/model/reasoning/voice writes;
- create directory та інші filesystem mutations;
- cron trigger;
- transcription і TTS/speech generation (можуть бути billable/duplicate work);
- prompt send, approvals, terminal/sudo input, attachments, secrets;
- будь-який controller/runtime command.

### 14. Як рахувати «одну повторну спробу»?

На один rejected credential/recovery epoch, не один раз на весь lifetime connection.

Якщо десять concurrent reads отримали `401` з тим самим credential:

1. вони коалесуються в **один** bootstrap;
2. усі отримують той самий новий credential;
3. кожне читання може повторитися **один раз**;
4. жодне не робить власний parallel bootstrap.

Якщо вже новий credential пізніше окремо стане stale, допускається новий recovery epoch. Failed bootstrap також має бути shared, щоб waiters не створили послідовний storm.

### 15. Що після другої відмови?

Показати terminal-for-this-attempt стан:

> Hermes tunnel authorization failed after refreshing the session. Verify that the tunnel points to the expected Hermes instance, then retry.

Дії: **Retry**, **Connection setup**, **Cancel**. Cached metadata залишається видимою як offline. Automatic auth loop зупиняється.

### 16. Що після відхиленої mutation?

Не replay. Показати operation-specific error і явну кнопку **Retry action**. Можна у фоні підготувати новий credential для наступної дії, але початкова mutation повторюється лише після нової дії користувача.

### 17. Cooldown після невдалого bootstrap

- transport/bootstrap unavailable → backoff `1,2,5,10,30s`, cap `30s`, загальний budget `5 min`;
- manual Retry може спробувати негайно й скидає timer;
- second credential rejection після успішного rebootstrap → жодних подальших automatic attempts до manual Retry або нового origin/mode generation;
- foreground event сам по собі не повинен нескінченно перезапускати terminal credential failure.

---

## 5. Підтверджений Hermes server contract

### 18. Які маршрути public/protected і що з `403`?

На `hermes-agent@4209d371` єдиний shared public allowlist:

- `/api/health`
- `/api/status`
- `/api/config/defaults`
- `/api/config/schema`
- `/api/model/info`
- `/api/dashboard/themes`
- `/api/dashboard/plugins`
- `/api/cron/fire` — public лише щодо dashboard gate, але endpoint має власний JWT contract.

Інші `/api/*` routes за замовчуванням проходять dashboard auth middleware, за винятком вузьких callback/special-auth seams. У gated mode окремо доступні login/native-auth bootstrap routes (`/login`, `/auth/*` відповідного flow), `/api/auth/providers`, MCP OAuth callback і static assets — вони потрібні, щоб користувач міг увійти до встановлення session. Відсутній/невірний loopback token дає `401`, не `403`.

Захищені routes можуть дати `403` після успішної auth через resource/policy restrictions. Тому `403` не є доказом stale token.

### 19. Session header і legacy Bearer

Так. У loopback/non-gated mode:

- preferred: `X-Hermes-Session-Token: <token>`;
- backward-compatible: `Authorization: Bearer <same token>`.

HAM має відправляти dedicated session header. Legacy Bearer лишається server compatibility, але новий client не повинен обирати його для `LoopbackSession`.

У gated mode діє OAuth/session gate; legacy loopback token не є заміною native OAuth.

### 20. HTML bootstrap і офіційний endpoint

Current main усе ще вбудовує `window.__HERMES_SESSION_TOKEN__` у root HTML для non-gated mode. Більше того, current source прямо називає це **Desktop token handshake**; headless `hermes serve` також повертає мінімальну token-only root page.

Окремого офіційного REST endpoint для adoption token у current source не знайдено, і опублікованого плану його додати немає. Тому HTML extraction є реальним current contract, але все ще coupling до implementation; саме тому feature Experimental.

Client повинен:

- fetch exact root без redirects;
- bounded parse exact assignment;
- не виконувати JS;
- сам ставити cache-bypass headers.

Live `v0.20.4` root мав assignment, але не повернув `Cache-Control`; current main додає no-store в headless path. Не залежати від server cache header.

### 21. WebSocket token і close codes

У loopback/non-gated mode `?token=<session-token>` підтримується для `/api/ws` і speech socket. У gated mode використовуються fresh single-use ticket/internal credential; legacy `?token=` відхиляється.

Коди, які мають значення для HAM:

- `4401` — auth credential rejected;
- `4403` — Host/Origin/request policy або feature boundary, не refresh token;
- `4408` — peer/client boundary в деяких dashboard sockets;
- `4404` — feature disabled/not available;
- `1011` — server/internal error.

Отже лише `4401` запускає credential recovery.

### 22. Коли змінюється token?

Default token генерується при process start. Якщо оператор задав `HERMES_DASHBOARD_SESSION_TOKEN`, використовується він. Current source також має внутрішній SSH/desktop seam, який може застосувати supplied process token під час роботи.

TTL/idle expiration для loopback session token не знайдено. Але client не повинен покладатися на це: будь-який `401`/`4401` означає, що current credential більше не приймається.

### 23. Які версії підтримувати?

Для першого release встановити conservative minimum: **Hermes `0.20.4`**, бо саме проти нього зроблено live smoke і design. Newer releases підтримуються через semantic probes.

`/api/status` містить `version`, `release_date`, `auth_required`, `auth_flows`, а current builds також `install_id`. Спеціального `loopback_session_auth` capability flag немає. Тому:

- version — diagnostics/minimum gate;
- actual status/root/protected-read handshake — source of truth;
- unknown older version → `ProtocolIncompatible`, без speculative fallback.

### 24. Чи status надійно відрізняє Hermes?

Ні. Expected JSON shape + version + `install_id` + successful protected handshake добре знаходять помилки конфігурації, але local malicious service може це імітувати. Це protocol validation, не cryptographic server identity.

### 25. Rate limit або lockout

У current dashboard session-token middleware не знайдено auth failure rate limiter або temporary lockout. Це не гарантія для майбутніх/gated OAuth deployments. HAM усе одно застосовує backoff і bounded attempts, щоб не створювати storm.

### 26. Multi-subscriber capability

Ні. Current source має subscriber registries для окремих dashboard channels, але `/api/status` не рекламує stable capability, що HAM може безпечно трактувати як підтримку кількох controller/stream subscribers до shared runtime.

Відповідно до `AGENTS.md`, HAM **не вмикає multi-subscriber streaming**, доки released server явно не рекламує capability.

---

## 6. Як перевіряти, що підхід працює

### 27. Чи є реальний Hermes?

Так, у development environment доступний реальний loopback Hermes `v0.20.4` на `127.0.0.1:9119`. Він придатний для safe protocol smoke tests без mock.

Але це не повний Android-over-external-SSH E2E. У середовищі немає підключеного ADB device (`adb devices -l` повернув порожній список) і не можна передавати Cursor SSH credentials. Physical-device E2E виконує Dmytro за checklist Task 8.

### 28. Реальні sanitized samples

Спостережено на live `v0.20.4`:

```text
GET /api/status                                  -> 200
status fields include: version, release_date, auth_required,
auth_flows, install_id, gateway state and component summaries

GET /                                             -> 200
root bytes                                        -> 1740
window.__HERMES_SESSION_TOKEN__ assignments       -> 1

GET /api/profiles/sessions (no token)             -> 401
GET /api/profiles/sessions (wrong session header) -> 401
body                                              -> {"detail":"Unauthorized"}

GET /api/profiles/sessions (valid session header) -> 200
GET /api/profiles/sessions (legacy valid Bearer)  -> 200
```

Токен та `install_id` у документі навмисно не наведені. Live `403` не форсувався через potentially sensitive file routes; його semantics підтверджена source code policy branches. WS `4401/4403` підтверджені current source і upstream tests.

### 29. Фізичний пристрій і SSH app

Dmytro має виконати final manual matrix на телефоні з уже робочим SSH/Tailscale setup. Cursor/Hermes готують APK, checklist і diagnostics, але не можуть чесно оголосити device E2E PASS без observed result користувача.

Мінімум перевірити: cold app start, Hermes restart, tunnel stop/start, app process kill, device reboot, network transition, wrong local service, two local ports.

### 30. Остаточні команди перевірки

Для backend-only Task 4A/4B changes:

```bash
./gradlew --no-daemon --no-configuration-cache --no-build-cache --rerun-tasks testDebugUnitTest
./gradlew --no-daemon lintDebug assembleDebug
git diff --check
```

Для будь-яких UI/setup changes і фінального feature gate додатково:

```bash
./gradlew --no-daemon validateDebugScreenshotTest
```

Розбіжності немає: `AGENTS.md` вимагає screenshot gate для adaptive UI changes; design вимагає його для фінального feature acceptance, бо feature включає UI.

### 31. Чи є еталонні screenshots?

Так, вони вже є в `app/src/screenshotTestDebug/reference/...`, включно з server setup/settings та compact/medium/expanded screens.

Cursor не повинен автоматично оновлювати references. Він може згенерувати candidate diffs; фінальний visual review і дозвіл на reference update дає human maintainer/Dmytro.

---

## 7. Ризики й product expectations

### 32. Shared Android loopback risk

Ризик прийнятий свідомо як residual risk Experimental mode: інший local app може звернутися до forwarded port і прочитати bootstrap token.

Потрібні **обидва**:

- коротке explicit warning у setup UI перед збереженням режиму;
- повний опис у user documentation.

Не називати Android loopback app-private або sandboxed.

### 33. Experimental чи stable?

**Experimental у першому release.** Причини: HTML bootstrap coupling, third-party SSH lifecycle, shared loopback, відсутність first-class capability flag і необхідність device-specific background validation.

### 34. Чи входить user documentation?

Так, до acceptance всієї feature — Task 7. Мінімум:

- vendor-neutral local-forward fields з Termius example;
- `127.0.0.1` recommendation;
- Test tunnel workflow;
- Experimental/security warning;
- recovery instructions для tunnel/Hermes restart;
- battery/background caveats;
- чітке пояснення, що HAM не керує SSH app і не зберігає SSH credentials/session token.

---

## Definition of done для Cursor

Cursor не повинен зупинятися після зеленого build. Наступна конкретна ціль — завершити **Task 4A**:

1. Змінити REST taxonomy на `401`-only credential rejection; `403` пропускати як operation forbidden.
2. Провести inventory всіх protected methods і retryable ViewModel reads.
3. Коалесувати concurrent refresh включно з failed bootstrap result.
4. Додати post-response stale guards до state mutations.
5. Зберегти OAuth transcript reconnect behavior.
6. Додати focused RED→GREEN tests і запустити full gates.
7. Надати diff для незалежного spec + security review; не називати Task 4A завершеним до двох PASS.

Після цього перейти до Task 4B за roadmap вище.

## Authoritative references

- HAM repository contract: [`AGENTS.md`](../AGENTS.md)
- Feature design: [`external-ssh-tunnel-session-auth.md`](design/external-ssh-tunnel-session-auth.md)
- Previous checkpoint handoff: [`CURSOR_CONTINUATION.md`](CURSOR_CONTINUATION.md) — використовувати лише разом із цим документом; цей документ має пріоритет.
- Hermes public API allowlist at inspected upstream commit: https://github.com/NousResearch/hermes-agent/blob/4209d371aa1bb8840ce8447555bdd863a1a96c38/hermes_cli/dashboard_auth/public_paths.py#L33-L60
- Hermes loopback session header + legacy Bearer auth: https://github.com/NousResearch/hermes-agent/blob/4209d371aa1bb8840ce8447555bdd863a1a96c38/hermes_cli/web_server.py#L657-L674
- Hermes `401` dashboard middleware: https://github.com/NousResearch/hermes-agent/blob/4209d371aa1bb8840ce8447555bdd863a1a96c38/hermes_cli/web_server.py#L998-L1005
- Hermes session-token lifecycle: https://github.com/NousResearch/hermes-agent/blob/4209d371aa1bb8840ce8447555bdd863a1a96c38/hermes_cli/web_server.py#L537-L557
- Hermes status endpoint and auth/version fields: https://github.com/NousResearch/hermes-agent/blob/4209d371aa1bb8840ce8447555bdd863a1a96c38/hermes_cli/web_server.py#L3743-L4017
- Hermes WebSocket auth contract: https://github.com/NousResearch/hermes-agent/blob/4209d371aa1bb8840ce8447555bdd863a1a96c38/hermes_cli/web_server.py#L16471-L16560
- Hermes `/api/ws` close behavior: https://github.com/NousResearch/hermes-agent/blob/4209d371aa1bb8840ce8447555bdd863a1a96c38/hermes_cli/web_server.py#L17693-L17713
- Hermes root HTML token handshake: https://github.com/NousResearch/hermes-agent/blob/4209d371aa1bb8840ce8447555bdd863a1a96c38/hermes_cli/web_server.py#L17919-L18016
