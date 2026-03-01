# Suraksha Architecture

## What It Is

**Suraksha** is an internal **shared auth library** for Skydo services. It’s a JAR that apps add as a dependency; it configures Spring Security and runs a small filter chain so every request is authenticated and optionally checked for XSS. It does **not** implement login/signup; it **validates** tokens and basic auth and populates the security context.

Think of it as: *"Every request must be authenticated; here’s how we decide who you are and what you can do."*

---

## Architecture in One Picture

```
Request
   │
   ▼
┌─────────────────────────────────────────────────────────────────┐
│  SkydoTokenFilter (auth)                                         │
│  • Resolve auth strategy (DeveloperAPI / BasicAuth / Bearer)     │
│  • Call auth service for Bearer → get session + roles            │
│  • Set SecurityContext + UserContext (ThreadLocal)               │
└─────────────────────────────────────────────────────────────────┘
   │
   ▼
┌─────────────────────────────────────────────────────────────────┐
│  InjectionFilter → XSSRequestWrapper                             │
│  • Wraps request; strips / rejects HTML in body, params, headers │
│  • SkipXssFilter annotation disables XSS check per endpoint      │
└─────────────────────────────────────────────────────────────────┘
   │
   ▼
┌─────────────────────────────────────────────────────────────────┐
│  CustomSecurityHeadersFilter                                     │
│  • X-Content-Type-Options, HSTS                                  │
└─────────────────────────────────────────────────────────────────┘
   │
   ▼
  Controller / GraphQL resolver
```

Auth runs first. Then the request is wrapped for XSS. Then security headers are set. After that, your controllers/resolvers run with `SecurityContext` and (for GraphQL) injectable `AuthUser`.

---

## Auth Model (The Important Part)

Three mutually exclusive strategies, resolved in order:

| Strategy        | How it’s chosen                               | What happens |
|-----------------|-----------------------------------------------|--------------|
| **DeveloperAPI** | Controller method has `@DeveloperAPI`         | No credentials; principal is `"DEVELOPER"`. Use for internal/dev-only endpoints. |
| **BasicAuth**    | Controller method has `@BasicAuth(username, password)` | Validates `Authorization: Basic <base64>` against annotation values. Good for machine-to-machine with fixed credentials. |
| **Token (default)** | No special annotation                      | Expects `Authorization: Bearer <token>` and `x-secret-key`. Token + secret are sent to the auth service; response drives identity and roles. |

So: **annotations opt into the two special cases**; everything else is Bearer + auth service.

- **Token path:** `SkydoTokenFilter` → `SkydoAuthManagement.handleAuth()` → `SkydoSessionManagement.checkSessionData()` → **HTTP call** to auth service `POST auth/session/fetch_session_data/{token}` with header `x-secret-key`. Response is `SessionResponseDto` (valid flag + `SessionDataDto`: authId, email, roles, userType, etc.). That becomes `AuthUser` in the security context and `UserContext.sessionData` (ThreadLocal).
- **Principal in context:** For token auth it’s `AuthUser(authId, token, userType)` with `UserType`: INTERNAL, EXPORTER, PAYER. Controllers can use `Authentication.getAuthId()` / `getAuthUser()`.
- **GraphQL:** Resolvers can take a parameter annotated with `@GraphqlAuthUser`; `AuthUserInjector` (SPQR) fills it from `SecurityContextHolder`, so you get the same `AuthUser` without manual wiring.

Design takeaway: **auth is centralized in one service**. This library is the client of that service. Every service that uses Suraksha must have `auth.server.url` and `auth.server.secret` so the library can call that session endpoint.

---

## Security and Request Handling

- **Default:** All requests require authentication (`anyRequest().authenticated()`). No public paths unless you override the security config in your app.
- **Session:** Stateless; no server-side session. Identity comes from the token validation call each time.
- **CSRF:** Disabled (typical for stateless APIs).
- **XSS:** `InjectionFilter` wraps the request in `XSSRequestWrapper`, which:
  - For JSON: runs a blocklist over body (and params/headers) looking for HTML-like tags; if found and XSS is enabled, it throws.
  - Endpoints can opt out with `@SkipXssFilter` (e.g. endpoints that legitimately accept HTML).
- **Headers:** `CustomSecurityHeadersFilter` sets `X-Content-Type-Options: nosniff` and HSTS.

Important: XSS is **blocklist-based** (tag names). It reduces risk but is not a full XSS sanitization layer; safe coding and output encoding still matter.

---

## Dependencies and Configuration

- **Provided:** `spring-boot-starter-web`, `spring-boot-starter-security`, and SPQR (GraphQL). So the app must use Spring Boot 2.7.x and bring those.
- **Bundled:** Retrofit + Gson (auth service client), Kotlin stdlib.

Required config in the app:

- `auth.server.url` — base URL of the auth service (e.g. `https://auth.skydo.internal`).
- `auth.server.secret` — value sent as `x-secret-key` when calling the session API.

The library builds a dedicated Retrofit instance for the auth service and uses it in `AuthServiceApi` (sync `Call.execute()`). So auth-service downtime or high latency directly affects request success and latency.

---

## Code-Level Flow (For Debugging)

1. **SkydoTokenFilter**  
   Parses path (for handler lookup), then `SkydoAuthManagement.handleAuth(request)`.

2. **SkydoAuthManagement**  
   Uses `AnnotationHandler` to find the handler for the request and check for `@DeveloperAPI` or `@BasicAuth`.  
   - If found: returns a fixed `UsernamePasswordAuthenticationToken("DEVELOPER", …)` or validates Basic and returns same.  
   - Else: reads `x-secret-key` and delegates to `SkydoSessionManagement.checkSessionData(request, secretKey)`.

3. **SkydoSessionManagement**  
   Resolves Bearer token from `Authorization`, calls `AuthServiceApi.getSessionData(token, secretKey)` (Retrofit sync), then:
   - Sets `UserContext.sessionData` (ThreadLocal).
   - Builds `UsernamePasswordAuthenticationToken(AuthUser(authId, token, userType), null, roles)` and returns it.

4. **SkydoTokenFilter**  
   Sets that authentication on `SecurityContextHolder` and continues. On `AuthException`, clears context and sends the exception’s HTTP status and message.

5. **InjectionFilter**  
   Checks for `@SkipXssFilter` on the handler; if absent, enables XSS filtering in `XSSRequestWrapper`. Wraps the request and continues the chain.

6. **CustomSecurityHeadersFilter**  
   Adds the security headers and continues.

So: **auth first, then wrapped request (XSS), then headers, then your code.**

---

## Things to Watch (Staff-Level)

- **ThreadLocal:** `UserContext.sessionData` is a ThreadLocal. It’s set in the filter and not explicitly cleared. In async or custom threads you must either propagate the context or re-set it; otherwise you’ll see missing or wrong user data.
- **AnnotationHandler and actuator:** The comment in `AnnotationHandler` notes that with `controllerEndpointHandlerMapping` (e.g. actuator), there can be two `RequestMappingHandlerMapping` beans. The code uses `BeanFactoryAnnotationUtils.qualifiedBeanOfType(..., "requestMappingHandlerMapping")` to pick the MVC one. If you add more handler mappings, similar clashes could appear.
- **Auth service dependency:** Session validation is a synchronous HTTP call per request. Latency and availability of the auth service are part of your p99 and error budget. Consider timeouts and fallback behavior in the app or auth service.
- **Error messages:** `AuthException("", HttpStatus.FORBIDDEN)` is used in several places with an empty message. That keeps details from leaking but makes debugging harder; consider at least stable error codes or logs (with no sensitive data).
- **BasicAuth:** Credentials live in annotation attributes (e.g. `@BasicAuth(username = "x", password = "y")`). If those come from config, they need to be injected via something like `@Value` into the annotation (if your setup supports it) or you need a different mechanism; otherwise secrets can end up in code/source.

---

## Summary

Suraksha is the **shared auth and request-hardening layer** for Skydo: one filter does auth (DeveloperAPI / BasicAuth / Bearer → auth service), the next does XSS filtering and the last adds security headers. Identity is in `SecurityContext` and `UserContext`; GraphQL gets it via `@GraphqlAuthUser`. Your services stay consistent on “who is calling” and “what we do with the request body,” while the actual login and session storage live in the central auth service this library calls.
