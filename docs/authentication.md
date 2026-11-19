# Authentication and token renewal

How the app signs in to an Audiobookshelf server and renews an expired access token. Status: single-flight renewal is on `plus` (merged from `fix/auth-refresh-single-flight`), not released yet.

## Credentials

- **Saved server connections** (`DeviceData.serverConnectionConfigs`, native `DeviceManager`): id = base64 of `address@username`, so another account on the same server is a different connection. Each holds the current **access token** (`token`).
- **Refresh token:** only in native `SecureStorage` (Android keystore, AES-GCM), keyed by connection id. It's written at login (`AbsDatabase.setCurrentServerConnectionConfig`) and by a successful refresh. JS never receives it after login.
- **Signed-in session (JS):** `store.user.serverConnectionConfig` and `store.user.accessToken`.
- Servers from 2.26.0 issue a short-lived access token (1 hour by default) and a refresh token that **rotates on every use** (`POST /auth/refresh`, header `x-refresh-token`). From 2.36 the server also accepts the previous refresh token for a grace period (`REFRESH_TOKEN_GRACE_PERIOD`, 10 minutes by default; 10 seconds in 2.35) and answers it with the already rotated token. Once that period has passed, the previous token is refused with the same 401 as a revoked session, which signs the app out.

## Request paths

| Path | Used by | Token |
|---|---|---|
| `$nativeHttp` (`plugins/nativeHttp.js`, CapacitorHttp) | most UI requests, `/api/authorize` at connect | store token, or the saved connection's token passed by `ServerConnectForm` |
| `$axios` (`plugins/axios.js`) | e-book readers, web playback | store token |
| PDF reader (`PdfReader.vue`) | pdf.js loads with its own header | store token |
| Native `ApiHandler` (OkHttp) | progress sync, playback sessions, Android Auto browsing | the saved connection's token |
| Native `DownloadItemManager` | downloads, which can belong to a non-active server | that connection's token |

## Renewal after a 401

**One refresh per saved connection, app-wide.** `server/TokenRefresher.kt` performs `/auth/refresh` inside a process-wide `SingleFlight` keyed by connection id. Every native `ApiHandler`, the downloader and the web app (through the `AbsDatabase.refreshAccessToken` plugin method) join the refresh that's already running instead of starting another. The key is released when the refresh, including its retries, ends and before results are delivered, so a failure is never remembered and the next 401 starts a fresh refresh.

**JS coordinator.** `utils/authRenewal.js` (wired by `plugins/authRenewal.js` as `$authRenewal`) coalesces JS callers per connection, so the bridge is crossed once. `$nativeHttp`, `$axios` and the store's `user/refreshToken` (PDF reader) all use it.

**Each request is retried once.**
- If the connection already has a newer token than the one the request was sent with, the request is retried with that token and nothing is renewed.
- Otherwise the request waits for the shared renewal, then retries.
- A retry that fails is returned as an ordinary error. `$axios` marks `_retry`; the PDF reader stops if its reload with the renewed token is refused.
- The auth endpoints (`/auth/refresh`, `/login`, `/logout`) never trigger renewal.
- A 401 for a request without a saved connection's bearer token (a login with a wrong password, for example) is an ordinary error.

**Outcomes**

| Result | When | Effect |
|---|---|---|
| `success` | the server rotated the token | Rotated refresh token stored; access token saved on the connection; store updated only if that connection is the signed-in session |
| `rejected` | `/auth/refresh` returned 401/403, or no refresh token or connection | That refresh token removed; the live session is revoked (`user/revokeSession`) and the server is `unauthorized`. The app stays where it is: no jump to `/connect`. Only that connection's session is touched. |
| `transient` | offline, timeout, 5xx, malformed answer after the retries; 429, a blocked redirect or another 4xx at once | Nothing changes; the waiting requests fail with an ordinary error and the user stays signed in. Offline playback never renews. |
| `stale` (JS) | logout or server switch while waiting | Waiting requests fail at once; the late result is ignored |

**Server reachability.** A `rejected` result also marks the server `unauthorized` in native `sync/ServerReachability.kt` (shown by the app-bar indicator as "sign in again"). This includes a server answering 401 when the connection has no refresh token (an old, pre-JWT token): `TokenRefresher` reports that as `rejected` too, so the server becomes `unauthorized` at once. `ApiHandler.handleRefreshRejected` reports it before checking whether the connection is the signed-in one, because offline on a cold start there is no signed-in connection while `sync/ActiveServer.kt` still remembers the server; a rejection for any other saved connection (a queued download's server, for example) does not change the state. `unauthorized` stays until the user signs in to that server again (or removes it). What happens to the session: `$authRenewal.signInAgain` dispatches `user/revokeSession`, which ends only the live session (renewals invalidated, socket closed, `user` and the access token cleared). The saved server, the selected library, the cached user object (rights and settings), progress, bookmarks and the outbox stay, so downloaded books keep playing and changes keep waiting; the library tabs stay empty without a user, as offline. Tapping the "sign in again" indicator opens `/connect?error=refreshTokenFailed&serverConnectionConfigId=<id>`, which explains why and preselects that server; after signing in the outbox is sent. An explicit sign-out (`user/logout`) is unchanged.

**Stale results can't overwrite newer credentials.**
- A refresh writes its rotated token only while the refresh token it sent is still the stored one (compare-and-set in `SecureStorage`, under one process-wide lock).
- So a refresh that finishes after a new login, after a logout from the server (token cleared) or after the connection was removed is discarded. A rejection that arrives after a new login doesn't clear the new token.
- Native requests carry an `AuthTag` (connection id, token, generation). The generation is bumped on logout, on a server switch and when the active connection is removed. A request sent before then isn't retried, and a 401 always renews the connection the request was sent to, never the one active at the time of the 401.
- JS invalidates `$authRenewal` on the `user/logout` mutation and when `user/setServerConnectionConfig` changes the connection. `onTokenRefresh` and `onTokenRefreshFailure` events carry the connection id and are ignored for a connection that isn't the signed-in one.

**Refresh redirects (R01).** Renewal sends the saved connection's refresh token only to its original
`address + /auth/refresh` endpoint, parsed by the existing HTTP client. Native renewal derives a
client with both HTTP and protocol redirects disabled; the browser-development fallback uses
Fetch `redirect: 'error'`. No `Location` is followed, including same-origin, relative, cross-origin,
protocol-changing or chained redirects. A redirect is a recoverable `transient` failure, not a
token rejection: credentials remain stored. Native diagnostics explain that the direct server
address must be configured before retrying, without logging the address, Location or credentials.
Configure the final server URL (including the reverse-proxy subpath); a proxy should serve refresh
there directly. Direct HTTP and HTTPS renewal, existing URL parsing, single-flight behavior and
same-owner retries are unchanged. General API and download redirect policies are unchanged.

**A lost answer is retried with the same token.** The server rotates the refresh token before it builds its (large) answer, so a timeout, a dropped connection, a 5xx/408 or an unreadable body may mean the new token was issued but never arrived. `TokenRefresher` asks again with the same token after 2 s and 10 s (at most 3 attempts) inside the same single flight, so callers that join meanwhile get the outcome of the whole series. Within the server's grace period the previous token returns the already rotated one; if the first request never arrived, the retry simply rotates. A 401/403 is not retried, nor is a 429 (the server's limit window is minutes long), a redirect, another 4xx, or a failure that proves the request never left (unknown host, connection refused), so offline errors still come back at once. Before each retry the saved connection and the sent token are checked again: after a new login or a removed server the series stops as `transient` without a request. A logout keeps the token and doesn't stop the series, like a refresh that was already running (see Logout semantics).

**Timeouts.**
- The refresh has its own client: 15-second connect/read/write timeouts and no whole-call deadline, whatever the caller's client uses (a 3-second ping client would otherwise cut off the large answer), and its own dispatcher, so it never queues behind ordinary requests.
- A native caller whose client has a call deadline (`pingClient`, used by `getMediaProgress` and `/ping`) gets an ordinary error once that deadline passes (`refreshWithin`), while the refresh finishes behind it and stores what it gets. Other native callers wait for the series.
- The JS coordinator gives up after 30 seconds (`AUTH_RENEWAL_TIMEOUT_MS`) as a backstop; a series can run longer. A native refresh still running then is joined by the next renewal, and a later request reuses the token it stored.
- Timeouts don't run while Android has frozen the app (background limits, Doze). A refresh that started before a freeze longer than the server's grace period can't be recovered by the app: the first answer after the freeze is a 401. Server owners can raise `REFRESH_TOKEN_GRACE_PERIOD` (for example 604800, 7 days), at the cost of an old refresh token staying usable for that long after a rotation.

**Connecting a saved server.**
- `ServerConnectForm` calls `/api/authorize` with the saved access token before the connection becomes the session. A 401 there renews that connection without touching the store.
- `setUserAndConnection` then saves `$authRenewal.latestToken(id)`, not the expired token. App startup does the same (`layouts/default.vue` `attemptConnection`). `latestToken` returns the signed-in session's token only when the session is that server, so another server's or account's token is never saved.

**Logging.** Refresh logs carry no tokens, server addresses or usernames. The 401 log line has the request path only.

## Logout semantics (upstream, unchanged)

| Action | UI | Credentials |
|---|---|---|
| A. Disconnect / B. Logout | side drawer Disconnect, Account → Logout, the old-token check at startup (`user/logout`, without `logoutFromServer`) | The session ends at once: the store is cleared, native forgets the active and last server, and every renewal still running is invalidated. The saved server keeps its refresh token, so tapping it on the connect page reconnects without a password (upstream's intent: "support switching servers without logging back in"). |
| C. Logout from the server | `user/logout(true)`: not used by any screen | Also ends the server's session (`POST /logout`). The refresh token stays in secure storage but is no longer valid. |
| D. Remove a saved server | connect page, delete | Config and refresh token removed (`removeServerConnectionConfig`). A refresh finishing afterwards is discarded and can't store anything. |

- **A late renewal after a logout** is stored for that saved server, because it's the same credential, rotated (a server answer is only written while the token it replaced is still stored).
- It is never applied to the signed-out app, and it doesn't make the server the last or active one again. After an app or WebView restart, the app stays signed out until that server is chosen on the connect page.

## Tests

- **`tests/authRenewal.test.mjs`** (JS, fake native refresh, fake HTTP and timers, no sleeps). It covers:
  - single and concurrent expiry, with one renewal for many requests;
  - joining a renewal that's in progress, and reusing a newer token;
  - shared rejection with one sign-out, recovery after a failure, offline/online and timeout;
  - logout, server switch and account switch during renewal, and delayed stale answers;
  - saved-server connect, login 401, WebView recreation, `$axios` and `$nativeHttp` sharing one renewal, the one-retry limit;
  - unhandled rejections and tokens in logs.
- **`android/.../server/TokenRefresherTest.kt`** (Robolectric for `org.json`; OkHttp interceptor as the server, latches instead of sleeps). It covers:
  - one `/auth/refresh` for concurrent callers, and callers joining mid-flight;
  - a fresh refresh with the rotated token afterwards;
  - rejection fan-out and sign-out once;
  - transient failures (offline, 5xx, malformed) and recovery;
  - retries with the same token after a lost answer (against a fake server with the grace period), a body that breaks off, 5xx/408 and malformed answers; no retry for 401/403/429/404, a request that never reached the server or a storage failure; a series that ends as `transient`; a refusal on a retry; changed credentials stopping the series; a caller joining during the pause; a failing retry still releasing the flight; `refreshWithin` answering a short-deadline caller once;
  - compare-and-set against a new login, logout from the server, and a removed connection;
  - independent servers and accounts;
  - no `Authorization` header on the refresh and no credentials in its logs;
  - `SingleFlight` error isolation and `renewalAction`.

- **`TokenRefresherRedirectTest.kt`** (real loopback HTTP/TLS) also covers a large answer trickling in over a slow network being read to the end, a short caller deadline not cutting the refresh short, and no retry after a blocked redirect.

## Not covered

- Cookie-based (web) refresh and OIDC sign-in are unchanged.
- A freeze longer than the server's grace period after a lost refresh answer still signs the app out (see Timeouts).
- Upstream's `user/logout` with `logoutFromServer` sends the stored refresh token. If a refresh is rotating it at that moment, the server may keep the old session until it expires.
