# AOP: Spring (Java), AspectJ, and Django (Python)

A short guide to **Aspect-Oriented Programming** and how it shows up in Spring, AspectJ, and Django.

---

## 1. What is AOP?

**AOP** separates **cross-cutting concerns** (logging, security, transactions, metrics) from **business logic**.

| Term | Meaning |
|------|---------|
| **Join point** | A point in execution (e.g. a method call). |
| **Pointcut** | An expression that selects which join points get advice. |
| **Advice** | Code that runs at those points (before, after, around). |
| **Aspect** | A module that groups pointcuts + advice. |

Instead of repeating `log start → do work → log end` in every service method, you declare the rule once and apply it where it matches.

---

## 2. Spring AOP (Java)

Spring AOP is **proxy-based**: Spring wraps your bean in a **proxy**. External calls go through the proxy, which runs advice then delegates to the real object.

### How it works

1. Spring creates a proxy (JDK dynamic proxy for interfaces, or CGLIB subclass for concrete classes).
2. A call like `userService.save(user)` hits the proxy.
3. Matching **aspects** run (before / after / around).
4. The real method runs (via `proceed()` in around advice).

```text
Client → Proxy → [Advice] → Target bean method → [Advice] → return
```

### Typical setup

- Dependencies: `spring-boot-starter-aop` (includes Spring AOP + AspectJ *weaver* for annotation parsing, not full compile-time AspectJ by default).
- Enable aspects with `@Aspect` on a `@Component` bean.

### Example (conceptual)

```java
@Aspect
@Component
public class LoggingAspect {

    @Around("execution(* com.example.service..*(..))")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            return joinPoint.proceed();
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            // log joinPoint.getSignature(), elapsed
        }
    }
}
```

### Strengths

- Fits Spring’s DI model; easy to wire with `@Transactional`, security, etc.
- No special compiler; works with normal `javac`.

### Limitations

- **Only Spring-managed beans** are proxied.
- **Self-invocation**: `this.internal()` inside the same class does **not** go through the proxy → aspects often **do not run**.
- **Join points**: Spring AOP is method execution on beans only (not field access, constructors, etc., unlike full AspectJ).

### When to use

- Transaction boundaries, security checks, logging/metrics on **service-layer** methods called from controllers or other beans.

---

## 3. AspectJ (Java)

**AspectJ** is a full AOP language for Java: richer pointcuts and **bytecode weaving** so behavior is woven into classes, not only via proxies.

### Weaving styles

| Style | When weaving happens |
|-------|----------------------|
| **Compile-time** | During `ajc` compile |
| **Post-compile** | Weave already-compiled `.class` files |
| **Load-time weaving (LTW)** | When the JVM loads classes (agent + `aop.xml`) |

### vs Spring AOP

| | Spring AOP | AspectJ |
|---|------------|---------|
| Mechanism | Proxies | Weaving into bytecode |
| Join points | Method execution on beans | Methods, constructors, fields, advice, etc. |
| Self-invocation | Bypasses proxy | Can still apply if woven into callee |
| Setup | Simple (starter-aop) | Stronger tooling / agent for LTW |

### When to use AspectJ

- You need advice on code **not** going through Spring proxies.
- Fine-grained join points (field get/set, `call()` vs `execution()`, etc.).
- Large codebases where proxy limitations hurt.

---

## 4. Django (Python) — “AOP-like” patterns

Django has **no AspectJ-style bytecode weaving**. You get similar **separation of cross-cutting behavior** with:

### 4.1 Middleware

Runs on **every request/response** (or a subset via ordering).

- **Use for**: auth session handling, CORS, request ID, global logging, timing headers.
- **Not for**: “only these view functions” unless you branch inside middleware.

```python
class RequestTimingMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        import time
        start = time.perf_counter()
        response = self.get_response(request)
        duration = time.perf_counter() - start
        response["X-Request-Duration-ms"] = int(duration * 1000)
        return response
```

### 4.2 Decorators

Wrap **specific views** (function or method-based).

- **Use for**: login required, roles, rate limiting, caching one endpoint.

```python
from functools import wraps

def log_calls(view_func):
    @wraps(view_func)
    def wrapper(request, *args, **kwargs):
        # before
        response = view_func(request, *args, **kwargs)
        # after
        return response
    return wrapper
```

### 4.3 Class-based view mixins / `dispatch()`

Override `dispatch()` to run code before/after any HTTP method on a CBV.

### 4.4 Signals

`pre_save`, `post_save`, etc. — **event hooks** on model lifecycle.

- **Caveat**: implicit flow; harder to trace than explicit service calls. Use sparingly for true cross-cutting events.

### 4.5 Custom template tags / context processors

Presentation-layer cross-cutting (not the same as method AOP, but similar “apply everywhere” idea).

### Mapping concepts

| AOP idea | Django-ish equivalent |
|----------|------------------------|
| Around advice on many views | Middleware + path checks, or shared mixin |
| Pointcut on one function | Decorator |
| After method success | Middleware response phase, or decorator after `view_func()` |
| Model lifecycle hooks | Signals (use with discipline) |

---

## 5. Quick comparison

| Concern | Spring AOP | AspectJ | Django |
|---------|------------|---------|--------|
| **Mechanism** | Runtime proxy | Bytecode weave | Middleware / decorators / signals |
| **Granularity** | Bean method calls | Very fine | Request-level or per-view / per-model |
| **Transparency** | Hidden proxy | Woven bytecode | Explicit registration (URL decorators, `MIDDLEWARE`) |
| **Self-call gap** | Yes (proxy) | Less of an issue | N/A (different model) |

---

## 6. Practical recommendations

### Spring

- Prefer **Spring AOP** + `@Transactional`, `@Cacheable`, security for most apps.
- Reach for **AspectJ** (or LTW) when proxies are not enough.

### Django

- **Middleware** for request-wide behavior.
- **Decorators / mixins** for targeted behavior on views.
- **Signals** only when the event model fits; prefer **explicit service functions** for core business rules.

---

## 7. Further reading

- Spring: [Aspect Oriented Programming with Spring](https://docs.spring.io/spring-framework/reference/core/aop.html)
- AspectJ: [The AspectJ Programming Guide](https://www.eclipse.org/aspectj/doc/released/progguide/index.html)
- Django: [Middleware](https://docs.djangoproject.com/en/stable/topics/http/middleware/), [Signals](https://docs.djangoproject.com/en/stable/topics/signals/)

---

*Document version: 1.0 — Spring AOP, AspectJ, Django patterns.*
