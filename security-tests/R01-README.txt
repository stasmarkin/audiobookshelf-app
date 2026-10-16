R01: refresh-token redirects

Run the real native refresh call path against bounded HTTP and HTTPS loopback servers:
  cd android
  gradlew :app:testDebugUnitTest --tests "app.absplus.android.server.TokenRefresher*"

Run the browser-development fallback through real Fetch (no production credentials):
  node --test tests/refreshRedirect.test.mjs tests/authRenewal.test.mjs

TokenRefresherRedirectTest uses bounded loopback sockets, JDK TLS and the existing OkHttp dependency.
No mock response from an application interceptor stands in for a redirect-chain test.
The p12 base64 resource is a PUBLIC SYNTHETIC TEST PRIVATE KEY and localhost certificate,
not a production credential. Password: synthetic-only. It is loaded by tests only,
with its own trust manager; production trust/signing configuration is not changed.
Generated with JDK keytool, RSA 2048, SAN localhost/127.0.0.1, valid 2020-2049.
Never reuse this test key for any real service.

Every redirect is rejected, even same-origin/relative redirects. Configure the direct
server URL and reverse-proxy subpath. Direct renewal and credential rotation still work.
The original saved address + /auth/refresh construction and HTTP parser are unchanged.
Redirect responses retain credentials and settle as transient; later direct retry works.
This does not change ordinary API/download redirects or logout/OIDC authentication.

Tests cover the original cross-origin custom-header leak, 300/301/302/303/307/308,
same-origin/relative paths, encoded traversal, loops/chains, userinfo, host/port changes,
HTTP-to-HTTPS and HTTPS-to-HTTP, token rotation/retry, removal and log redaction.
The web tests exercise Node Fetch, not browser CORS; Android device validation is separate.
Existing H01/H04 tests remain unchanged and must also pass before integration.

Mutation checks (isolated prepared checkout, no concurrent editor/build):
  python security-tests/run_r01_mutations.py --output "<outside-checkout evidence directory>"
The runner restores original bytes in finally. It requires an assertion failure, not just
a failed build or network error. The original-client mutation records the synthetic refresh
header observed at the second origin. Disabling only followSslRedirects is intentionally
redundant while all redirects are disabled; no independent mutation-kill claim is made for it.
