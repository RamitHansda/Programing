# Skydo Auth Service - Architecture Documentation

## **Project Summary**

**Skydo Auth** is a centralized authentication and session management service built with **NestJS** (Node.js/TypeScript) and **PostgreSQL**. It serves as the identity and access management backbone for Skydo's multi-application ecosystem, supporting multiple authentication methods across different client applications (payer apps, internal portals, exporter apps, etc.).

---

## **Architecture Overview**

```
┌─────────────────────────────────────────────────────────────┐
│                    Client Applications                      │
│  (Payer App, Internal Portal, Ops Portal, External APIs)   │
└────────────────────┬────────────────────────────────────────┘
                     │ x-secret-key (merchant identification)
                     ▼
┌─────────────────────────────────────────────────────────────┐
│                  Skydo Auth Service (NestJS)                │
│                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐    │
│  │ Email OTP    │  │ Google OAuth │  │ Admin Auth   │    │
│  │ Auth         │  │              │  │              │    │
│  └──────────────┘  └──────────────┘  └──────────────┘    │
│                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐    │
│  │ Pre-signed   │  │ API/SDK      │  │ Session      │    │
│  │ Auth         │  │ Auth         │  │ Management   │    │
│  └──────────────┘  └──────────────┘  └──────────────┘    │
│                                                             │
│             Core: Users, Apps, Providers, Roles            │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
            ┌─────────────────┐
            │   PostgreSQL    │
            │   (TypeORM)     │
            └─────────────────┘
```

**Tech Stack:**
- **Framework:** NestJS 9.x (Node.js)
- **Language:** TypeScript
- **Database:** PostgreSQL with TypeORM
- **Authentication:** JWT tokens
- **External Services:** SendGrid (email), Google OAuth2, AWS Secrets Manager
- **Logging:** Winston + Datadog
- **Transaction Management:** typeorm-transactional (cls-hooked)

---

## **Core Business Capabilities**

### **1. Multi-Tenant Authentication**
- **Merchant Isolation:** Each merchant has a unique `x-secret-key` that scopes all authentication requests
- **Multi-Application Support:** Single user identity across multiple Skydo applications (payer app, internal tools, etc.)
- **Flexible Signup Models:** 
  - **Whitelisted:** Only pre-approved emails can sign up
  - **Anonymous:** Open registration

### **2. Six Authentication Methods**

| Method | Use Case | Implementation |
|--------|----------|----------------|
| **Email OTP** | Passwordless consumer login | 6-digit OTP via SendGrid with retry limits |
| **Google OAuth** | Social login | OAuth2 access token validation |
| **Admin Auth** | Privileged access for internal ops | SHA-256 signature validation |
| **Pre-signed Auth** | Trusted service-to-service | Hash-based token authentication |
| **API/SDK Auth** | Programmatic access for integrations | Long-lived secret keys + JWT sessions |

### **3. Session Management**
- **JWT-based sessions** with configurable expiry per provider
- **Concurrent session limits** per provider configuration
- **Bulk session invalidation** (per user or globally)
- **Session metadata tracking:** IP, device ID, user agent, referrer

### **4. User Linking (Primary/Secondary Users)**
- Link multiple email identities to a single primary user
- Useful for account consolidation or team member management
- Unlink functionality with automatic session invalidation

### **5. Merchant Secret Key Management**
- Generate merchant-specific API keys
- Resolve merchant context from `x-secret-key` header
- Enable/disable merchant access

---

## **Data Model (Key Entities)**

```
┌─────────────────┐
│  MerchantSecret │ (Multi-tenancy anchor)
└────────┬────────┘
         │
         ▼
┌─────────────────┐        ┌──────────────────┐
│  Application    │◄───────│ ApplicationProvider│
│ (Payer, Portal) │        │ (EMAIL_OTP, GOOGLE)│
└────────┬────────┘        └─────────┬──────────┘
         │                           │
         │                           │
         ▼                           ▼
┌─────────────────┐        ┌──────────────────┐
│   User          │◄───────│   UserProvider   │
│ (Identity)      │        │ (Auth history)   │
└────────┬────────┘        └──────────────────┘
         │
         ├──────► UserRole (per app)
         ├──────► UserSession (JWT tokens)
         └──────► ApiUserCredentials (SDK keys)
```

**Critical Tables:**
- `skydo_user`: User identity (email, name, merchantId, primaryUserId)
- `application`: Applications (Payer App, Internal Portal, etc.)
- `application_provider`: Links apps to auth methods with config (e.g., OTP expiry, session limits)
- `user_provider`: Tracks user signup/login per app-provider combo
- `user_session`: Active sessions with JWT tokens
- `merchant_secret_key`: Merchant API keys for tenant isolation

---

## **Authentication Flow (Example: Email OTP)**

```
Client App
    │
    │ POST /auth/email/request_otp
    │ Headers: x-secret-key
    │ Body: { email, applicationName }
    ▼
Auth Service
    │
    ├─► Validate merchant (x-secret-key)
    ├─► Check EMAIL_OTP provider enabled for app
    ├─► Enforce whitelist if SignupType.WHITELISTED
    ├─► Generate 6-digit OTP
    ├─► Store in email_otp table
    ├─► Send via SendGrid
    │
    │ Returns: { correlationId }
    ▼
Client App

    │ User enters OTP
    │
    │ POST /auth/email/login
    │ Body: { correlationId, otp }
    ▼
Auth Service
    │
    ├─► Validate OTP (expiry, retry limit)
    ├─► Mark OTP as used
    ├─► Create/find User
    ├─► Create/update UserProvider
    ├─► Create UserSession (JWT)
    │
    │ Returns: { token, userId, roles, expiresAt }
    ▼
Client App (stores JWT)
```

---

## **Module Architecture**

### **Service Dependency Graph**

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        AppModule (root)                                 │
│  EmailAuthModule, GoogleAuthModule, AdminAuthModule, PreSignedAuthModule,│
│  ApiAuthModule, SessionModule, LinkUserModule, MerchantSecretKeyModule   │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
        ┌───────────────────────────┼───────────────────────────┐
        ▼                           ▼                           ▼
┌───────────────┐          ┌────────────────┐          ┌─────────────────┐
│ EmailAuth     │          │ GoogleAuth     │          │ AdminAuth       │
│ PreSignedAuth │          │ ApiAuth        │          │                 │
└───────┬───────┘          └───────┬────────┘          └────────┬────────┘
        │                          │                            │
        │  loginUserViaEmail()     │                            │
        └──────────────────────────┴────────────────────────────┘
                                    │
                                    ▼
                        ┌───────────────────────┐
                        │ AuthModule (core)     │
                        │ - UserService         │
                        │ - UserProviderService │
                        │ - ApplicationService  │
                        └───────────┬───────────┘
                                    │
        ┌───────────────────────────┼───────────────────────────┐
        ▼                           ▼                           ▼
┌───────────────┐          ┌────────────────┐          ┌─────────────────┐
│ SessionModule │          │ MerchantSecret │          │ CoreModule      │
│ - JWT tokens  │          │ KeyModule      │          │ (email, etc.)   │
└───────────────┘          └────────────────┘          └─────────────────┘
```

### **Key Modules Breakdown**

| Module | Purpose | Key Features |
|--------|---------|-------------|
| **auth** | Core user/app management | User creation, provider linking, role management |
| **email-auth** | OTP-based login | OTP generation, SendGrid integration, retry limits, CQRS pattern |
| **google-auth** | Social login | OAuth2 token validation |
| **admin-auth** | Internal admin access | Signature-based auth for ops portal |
| **pre-signed-auth** | Service-to-service | Hash validation, session reuse |
| **api-auth** | SDK/API access | Secret key generation, renewal, validation |
| **session** | JWT session management | Token issuance, validation, logout, concurrent session limits |
| **link-user** | Account linking | Primary/secondary user relationships |
| **merchant-secret-key** | Tenant isolation | Merchant API key management |
| **migration** | Email migration | Utility for bulk email updates (likely for data cleanup) |
| **core** | Shared utilities | Email validation, mail sender service, common DTOs |
| **log** | Logging infrastructure | Winston + Datadog integration |

---

## **Authentication Methods Deep Dive**

### **1. Email OTP Authentication**
**Endpoints:**
- `POST /auth/email/request_otp` - Request OTP
- `POST /auth/email/login` - Verify OTP and login

**Flow:**
1. Validate merchant via `x-secret-key`
2. Check `EMAIL_OTP` provider enabled for application
3. Enforce whitelist if `SignupType.WHITELISTED`
4. Generate 6-digit OTP (configurable expiry, default 300s)
5. Store OTP with `correlationId`
6. Send via SendGrid
7. Validate OTP with retry limits (default 5 attempts)
8. Create/update user and session

**Security Features:**
- OTP expiry enforcement
- Maximum retry attempts tracking
- Single-use OTP tokens
- Rate limiting via `EmailOtpLoginAttemptEntity`

---

### **2. Google OAuth Authentication**
**Endpoint:**
- `POST /auth/google/login`

**Flow:**
1. Client obtains Google OAuth access token
2. Auth service validates token with Google API
3. Fetch user info from `https://www.googleapis.com/oauth2/v3/userinfo`
4. Validate `GOOGLE` provider for application
5. Create/find user by email
6. Create session

**Data Retrieved:**
- Email (used as primary identifier)
- Name
- Profile picture (if available)

---

### **3. Admin Authentication**
**Endpoint:**
- `POST /admin-auth/request_token`

**Flow:**
1. Client computes: `SHA256(email + ADMIN_EMAIL + ADMIN_SECRET)`
2. Sends hash as `x-sig` header
3. Server validates signature
4. Requires `ADMIN_AUTH` provider enabled
5. User must already exist (no signup)
6. Creates session

**Use Case:** Internal operations portal, privileged admin access

---

### **4. Pre-signed Authentication**
**Endpoint:**
- `POST /pre-signed-auth/request_token`

**Flow:**
1. Client computes: `SHA256(email + PRE_SIGNED_AUTH_TOKEN_SECRET)`
2. Sends hash as `x-sig` header
3. Server validates signature
4. Attempts to reuse existing valid session
5. If no valid session exists, creates new one

**Use Case:** Trusted service-to-service communication, SSO scenarios

---

### **5. API/SDK Authentication**
**Endpoints:**
- `POST /api-auth/secret-key/generate` - Generate API key
- `POST /api-auth/secret-key/renew` - Rotate API key
- `POST /api-auth/token/generate` - Create JWT token
- `POST /api-auth/token/validate/:token` - Validate token

**Flow:**
1. **Key Generation:**
   - Generate UUID secret key
   - Store in `api_user_credentials`
   - Only one active key per user
   
2. **Token Generation:**
   - Validate secret key
   - Create JWT session
   - Return token with expiry

3. **Token Validation:**
   - Verify JWT signature
   - Check user still active
   - Return session data

**Use Case:** SDK integrations, programmatic API access, third-party applications

---

## **Session Management**

### **Session Lifecycle**
1. **Creation:** Upon successful authentication
2. **Storage:** JWT token + database record in `user_session`
3. **Validation:** On each protected endpoint request
4. **Expiry:** Configurable per provider (default 7 days)
5. **Invalidation:** Logout, user unlinking, key renewal

### **JWT Payload**
```json
{
  "userId": "uuid",
  "roles": ["ADMIN", "USER"],
  "exp": 1234567890
}
```

### **Session Features**
- **Concurrent Session Limits:** Configurable per provider
- **Device Tracking:** IP, device ID, user agent
- **Referrer Tracking:** Source application tracking
- **Bulk Operations:** Invalidate all sessions for a user
- **Merchant Scoping:** Sessions tied to merchant context

---

## **Deployment & Operations**

### **Environment Configuration**
- `.env` (local development)
- `.env.staging`
- `.env.production`
- AWS Secrets Manager integration via `envConfigLoader`

**Key Environment Variables:**
- `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DATABASE`
- `JWT_SECRET`
- `ADMIN_EMAIL`, `ADMIN_SECRET`
- `PRE_SIGNED_AUTH_TOKEN_SECRET`
- `SENDGRID_API_KEY`
- `PORT` (default: 3000)

### **Docker Support**
- `docker/staging/Dockerfile`
- `docker/production/Dockerfile`

### **Database Migrations**
- **Manual SQL migrations** in `/migration` folder
- 14+ migration files tracking schema evolution
- Notable migrations:
  - `1_initial_schema_migration.sql`: Base schema
  - `24_may_24_add_secret_key_for_merchants.sql`: Merchant tenancy
  - `28_added_user_auth_provider.sql`: Provider system
  - `29_payer_api_auth.sql`: API authentication

### **Running the Application**

```bash
# Install dependencies
$ yarn install --frozen-lockfile

# Development
$ npm run start:dev

# Production
$ npm run build
$ npm run start:prod
```

### **Logging & Monitoring**
- **Winston Logger** with custom configuration
- **Datadog Integration** for distributed tracing
- **Structured Logging** via `CustomLoggerService`
- **Log Correlation** for request tracking

---

## **Security Architecture**

### **Security Strengths**
✅ **Multi-tenant Isolation:** Merchant secret keys prevent cross-tenant access  
✅ **JWT-based Sessions:** Stateless, scalable authentication  
✅ **Rate Limiting:** OTP attempt tracking prevents brute force  
✅ **OTP Security:** Time-limited, single-use tokens  
✅ **Signature Validation:** SHA-256 for admin/pre-signed auth  
✅ **Session Invalidation:** Automatic cleanup on security events  
✅ **Provider Configuration:** Granular control per app-provider combo  

### **Security Considerations**
⚠️ **Admin Auth:** Relies on environment variables – implement rotation policy  
⚠️ **Pre-signed Auth:** Shared secret distribution needs secure channel  
⚠️ **API Keys:** Long-lived credentials – consider expiry/rotation policies  
⚠️ **Session Expiry:** 7-day default may be too long for sensitive apps  
⚠️ **No MFA:** Current implementation lacks multi-factor authentication  
⚠️ **JWT Secrets:** Ensure proper secret management and rotation  

### **Recommended Security Enhancements**
1. Implement refresh token mechanism
2. Add MFA/2FA support for high-value accounts
3. Implement API key expiration policies
4. Add audit logging for security events
5. Implement CORS policies
6. Add request signing for API authentication
7. Implement secret rotation for admin/pre-signed auth
8. Add anomaly detection for login patterns

---

## **Scalability Considerations**

### **Current Architecture**
- **Stateless Design:** JWT tokens enable horizontal scaling
- **Database Bottleneck:** PostgreSQL is single point of contention
- **External Dependencies:** SendGrid, Google OAuth

### **Scaling Strategies**
1. **Horizontal Scaling:** Add more application instances (already supported)
2. **Database Read Replicas:** Separate read traffic from writes
3. **Caching Layer:** Redis for session validation, OTP storage
4. **Rate Limiting:** Implement distributed rate limiting (Redis)
5. **Connection Pooling:** Optimize TypeORM connection pool settings
6. **Async Processing:** Queue-based email sending (SQS/RabbitMQ)

### **Performance Metrics to Track**
- Authentication request latency (p50, p95, p99)
- Session validation latency
- Database query performance
- SendGrid email delivery time
- Concurrent session count per user
- OTP generation/validation rate

---

## **Testing Strategy**

### **Current Coverage**
- **Unit Tests:** Jest configured with coverage reporting
- **E2E Tests:** Available via `test/app.e2e-spec.ts`
- **Code Quality:** ESLint + Prettier

### **Test Commands**
```bash
# Unit tests
$ npm run test

# E2E tests
$ npm run test:e2e

# Coverage report
$ npm run test:cov

# Watch mode
$ npm run test:watch
```

### **Recommended Test Coverage**
- [ ] Unit tests for all authentication methods
- [ ] Integration tests for session lifecycle
- [ ] Security tests (signature validation, rate limiting)
- [ ] Load tests for concurrent authentication
- [ ] Chaos testing for external service failures

---

## **Technical Debt & Roadmap**

### **Immediate Priorities (P0)**
1. **Health Check Endpoint:** For load balancer/orchestration monitoring
2. **API Rate Limiting:** Protect all endpoints beyond OTP
3. **Audit Logging:** Security event tracking (login failures, key generation)
4. **API Documentation:** Swagger/OpenAPI specification
5. **Error Handling:** Standardize error responses (replace `throwException` pattern)

### **Medium-term Enhancements (P1)**
6. **MFA/2FA Support:** TOTP, SMS, or authenticator app
7. **Refresh Tokens:** Reduce JWT expiry risk
8. **Circuit Breaker:** Resilience for external dependencies
9. **Caching Layer:** Redis for session/OTP validation
10. **Metrics Dashboard:** Real-time monitoring (Datadog/Grafana)

### **Long-term Vision (P2)**
11. **OAuth2 Server:** Become identity provider for third parties
12. **RBAC Granularity:** Fine-grained permission system
13. **Secret Rotation:** Automated admin/pre-signed secret rotation
14. **Distributed Tracing:** OpenTelemetry integration
15. **SAML/OIDC Support:** Enterprise SSO integration
16. **Passwordless WebAuthn:** Hardware key support

---

## **API Endpoints Reference**

### **Email OTP Authentication**
- `POST /auth/email/request_otp` - Request OTP code
- `POST /auth/email/login` - Login with OTP

### **Google OAuth Authentication**
- `POST /auth/google/login` - Login with Google access token

### **Admin Authentication**
- `POST /admin-auth/request_token` - Admin token generation

### **Pre-signed Authentication**
- `POST /pre-signed-auth/request_token` - Pre-signed token generation

### **API Authentication**
- `POST /api-auth/secret-key/generate` - Generate API secret key
- `POST /api-auth/secret-key/renew` - Renew API secret key
- `POST /api-auth/token/generate` - Generate JWT token from secret key
- `POST /api-auth/token/validate/:token` - Validate JWT token

### **Session Management**
- `POST /auth/session/fetch_session_data/:token` - Validate and fetch session
- `POST /auth/session/logout` - Logout single session
- `POST /auth/session/logout_all` - Logout all sessions for user

### **User Linking**
- `POST /link-user/create-secondary-user` - Create secondary user
- `POST /link-user/unlink-user` - Unlink secondary user

### **Merchant Management**
- `POST /merchant-secret-key/create` - Create merchant secret key
- `GET /merchant-secret-key/:secretKey` - Get merchant details

---

## **Data Flow Diagrams**

### **User Registration & First Login**
```
1. Client → Auth Service: Request OTP (email, app name)
2. Auth Service → Database: Check application & provider
3. Auth Service → Database: Check whitelist (if applicable)
4. Auth Service → Database: Store OTP
5. Auth Service → SendGrid: Send OTP email
6. SendGrid → User: Email with OTP
7. User → Client: Enter OTP
8. Client → Auth Service: Submit OTP
9. Auth Service → Database: Validate OTP
10. Auth Service → Database: Create User record
11. Auth Service → Database: Create UserProvider record
12. Auth Service → Database: Create UserSession record
13. Auth Service → Client: Return JWT token
```

### **Existing User Login**
```
1-8. [Same as above]
9. Auth Service → Database: Validate OTP
10. Auth Service → Database: Find existing User
11. Auth Service → Database: Update UserProvider (last login)
12. Auth Service → Database: Create UserSession record
13. Auth Service → Client: Return JWT token
```

### **Protected Endpoint Access**
```
1. Client → Protected Service: Request + JWT token + x-secret-key
2. Protected Service → Auth Service: Validate session
3. Auth Service → Database: Verify JWT signature
4. Auth Service → Database: Check session validity
5. Auth Service → Database: Verify merchant context
6. Auth Service → Protected Service: Session data (user, roles, merchant)
7. Protected Service → Client: Protected resource
```

---

## **Troubleshooting Guide**

### **Common Issues**

#### **OTP Not Received**
- Check SendGrid API key configuration
- Verify email address validity
- Check SendGrid logs for delivery status
- Verify sender email is verified in SendGrid

#### **Session Validation Failing**
- Verify JWT_SECRET matches between environments
- Check session expiry time
- Verify merchant secret key is correct
- Check if user's sessions were invalidated

#### **Admin Auth Failing**
- Verify ADMIN_EMAIL and ADMIN_SECRET environment variables
- Check signature computation (email + ADMIN_EMAIL + ADMIN_SECRET)
- Ensure ADMIN_AUTH provider is enabled for application

#### **Database Connection Issues**
- Verify PostgreSQL credentials in environment config
- Check network connectivity to database
- Verify database exists and migrations are applied
- Check connection pool settings

---

## **Glossary**

- **Application:** A client system using Skydo Auth (e.g., Payer App, Internal Portal)
- **Provider:** Authentication method (EMAIL_OTP, GOOGLE, ADMIN_AUTH, etc.)
- **ApplicationProvider:** Configuration linking an application to a provider
- **UserProvider:** Record of user authentication via specific provider
- **Merchant:** Tenant in multi-tenant architecture
- **Primary User:** Main user account in user linking
- **Secondary User:** Linked user account that references primary user
- **Correlation ID:** Unique identifier linking OTP request to login attempt
- **JWT:** JSON Web Token used for session management
- **Session:** Authenticated user context with expiry

---

## **Contact & Support**

For technical questions or architectural decisions:
- Review this document
- Check NestJS documentation: https://docs.nestjs.com/
- Review TypeORM documentation: https://typeorm.io/

---

**Document Version:** 1.0  
**Last Updated:** February 10, 2026  
**Maintained By:** Engineering Team
