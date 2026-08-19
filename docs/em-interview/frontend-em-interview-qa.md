# Frontend Interview Prep — EM Leading a Full-Stack Team

Questions and answers for an Engineering Manager who leads full-stack engineers: technical depth + ownership, standards, trade-offs, and team leadership.

---

## 0. Frontend Basics (HTML, CSS, JavaScript, Browser & Network)

*Use this section for “fundamentals” questions—what every full-stack engineer (and EM) should recognize.*

### What is the role of HTML vs CSS vs JavaScript?

**Basics:**  
- **HTML** — Structure and meaning of content: headings, lists, links, forms, sections. Semantic tags (`<main>`, `<nav>`, `<button>`) help accessibility and SEO.  
- **CSS** — Presentation: layout, colors, fonts, spacing, responsive breakpoints.  
- **JavaScript** — Behavior: reacting to clicks, validating input, calling APIs, updating what the user sees.

**EM answer:**  
“I’d expect the team to keep a clear separation: structure in HTML, look in CSS, behavior in JS. Critical content shouldn’t live only in JS if we care about SEO or users without JS. As an EM, I’d bake this into review: semantic HTML for interactive controls, not `div` + click handlers everywhere.”

---

### What is the DOM, and how does it relate to what we build?

**Basics:**  
The **Document Object Model** is the browser’s tree representation of the page (nodes for elements, text, attributes). JavaScript reads and updates the DOM to change the UI. Frameworks (React, etc.) abstract this but still end up updating the real DOM.

**EM answer:**  
“Understanding the DOM helps with debugging, performance (too many updates), and accessibility (focus, ARIA). As an EM, I don’t need everyone to hand-roll DOM APIs, but I want the team to know that ‘re-renders’ ultimately touch the DOM and that’s why we care about batching and avoiding unnecessary work.”

---

### What are semantic HTML elements, and why do they matter?

**Basics:**  
Semantic elements carry meaning: `<header>`, `<footer>`, `<article>`, `<section>`, `<nav>`, `<main>`, `<button>` for actions, `<a>` for navigation, proper heading levels (`h1`–`h6`). They help screen readers, keyboard users, and search engines understand the page.

**EM answer:**  
“I’d treat semantic HTML as part of our quality bar: it’s cheaper than bolting on ARIA later. As an EM, I’d include this in our frontend checklist and component guidelines so full-stack engineers default to the right element.”

---

### Explain the CSS box model (content, padding, border, margin).

**Basics:**  
Each element is a box: **content** (text/size), **padding** (space inside the border), **border**, **margin** (space outside). `box-sizing: border-box` makes `width`/`height` include padding and border—usually easier for layouts.

**EM answer:**  
“Layout bugs often come from misunderstanding box model and `box-sizing`. As an EM, I’d set a global default (`border-box`) in our base styles so the team isn’t fighting inconsistent behavior across browsers.”

---

### What’s the difference between Flexbox and CSS Grid? When would you use each?

**Basics:**  
- **Flexbox** — One-dimensional layouts: row or column; great for nav bars, centering, distributing space along one axis.  
- **Grid** — Two-dimensional layouts: rows and columns together; great for dashboards, page regions, complex alignments.

**EM answer:**  
“I don’t need the team to memorize every property, but I want a default mental model: flex for linear groups, grid for 2D layouts. As an EM, I’d document our preferred approach in the design system so we don’t mix five layout patterns in one codebase.”

---

### What is CSS specificity, and why does it cause bugs?

**Basics:**  
When rules conflict, the browser applies the one with **higher specificity** (e.g. `id` beats class, class beats element). Inline styles and `!important` override normal rules and make overrides hard.

**EM answer:**  
“Specificity wars create unmaintainable CSS. As an EM, I’d push for scoped styles (modules, Tailwind, or design tokens) and discourage deep selector chains and random `!important` so junior and full-stack folks can change styles safely.”

---

### What are media queries, and what is mobile-first vs desktop-first?

**Basics:**  
**Media queries** apply CSS when conditions match (e.g. `min-width`, `max-width`). **Mobile-first** starts with narrow screens and adds rules for wider breakpoints. **Desktop-first** starts wide and adds rules for smaller screens.

**EM answer:**  
“We’d pick one approach and stick to it for consistency. As an EM, I’d align with design on breakpoints and document them so engineering and design speak the same language.”

---

### What is the difference between `==` and `===` in JavaScript?

**Basics:**  
`==` compares with **type coercion** (can produce surprising results). `===` compares **value and type** (strict equality). Best practice in modern code: prefer `===` and `!==` unless you have a deliberate reason otherwise.

**EM answer:**  
“I’d set lint rules to prefer strict equality so we avoid subtle bugs. As an EM, this is a small standard that scales across a full-stack team.”

---

### What are `let`, `const`, and `var`?

**Basics:**  
- **`const`** — Block-scoped; binding can’t be reassigned (object properties can still change).  
- **`let`** — Block-scoped; can be reassigned.  
- **`var`** — Function-scoped; hoisted; largely legacy—avoid in new code.

**EM answer:**  
“Default to `const`, use `let` when reassignment is needed, avoid `var`. As an EM, I’d enforce this via ESLint so code style is consistent.”

---

### What is a closure? (High level.)

**Basics:**  
A **closure** is when a function “remembers” variables from an outer scope even after that outer function finished. Common in callbacks, event handlers, and hooks that capture state.

**EM answer:**  
“Closures explain bugs like stale state in loops or effects. As an EM, I want senior folks to catch these in review; for the team, I’d document patterns (e.g. dependency arrays in React) that prevent the worst issues.”

---

### What are Promises and `async`/`await`?

**Basics:**  
A **Promise** represents a future value (success or failure). **`async`/`await`** is syntactic sugar for chaining Promises—easier to read than nested `.then()`. Errors are handled with `try/catch` around `await`.

**EM answer:**  
“Modern frontend is mostly async: API calls, user events. As an EM, I’d expect engineers to use `async`/`await` consistently and handle errors and loading states so we don’t leave users with silent failures or stuck spinners.”

---

### How does the browser load a page? (High level: HTML, CSS, JS, render.)

**Basics:**  
Browser fetches **HTML**, parses it, may discover **CSS** and **JS** and fetch them. **CSS** can block rendering if not optimized. **JS** can block parsing unless `defer`/`async`. Browser builds DOM + CSSOM, computes layout, paints.

**EM answer:**  
“This is why we care about script placement, bundling, and critical CSS: blocking resources hurt LCP and TTI. As an EM, I’d connect this to our performance budget and ensure we don’t load huge scripts in the head without a plan.”

---

### What is HTTP from the frontend’s perspective? GET vs POST?

**Basics:**  
**HTTP** is how the browser talks to servers. **GET** — Read data; typically cacheable; body usually empty; parameters often in URL. **POST** — Often used to create/submit data; body carries payload; not assumed idempotent for caching like GET.

**EM answer:**  
“I’d expect the team to choose the right verb for APIs we call and to understand idempotency for retries. As an EM, I’d align with backend on REST or GraphQL conventions so frontend and API contracts stay clear.”

---

### What is CORS, and why do frontend devs hit it?

**Basics:**  
**Cross-Origin Resource Sharing** — Browsers restrict JavaScript from calling APIs on a different origin (scheme + host + port) unless the server sends headers allowing it. It’s a browser security feature, not something Postman “fixes” in production.

**EM answer:**  
“CORS issues are common in local dev and new environments. As an EM, I’d ensure we document our API base URLs and env setup, and that backend and infra own the correct CORS headers for our domains so the team isn’t debugging blindly.”

---

### Cookies vs `localStorage` vs `sessionStorage`?

**Basics:**  
- **Cookies** — Sent to server on requests (size limits); can be HttpOnly (not readable by JS—good for sensitive session tokens).  
- **`localStorage`** — Persists until cleared; same-origin; not sent automatically to server; larger than cookies.  
- **`sessionStorage`** — Cleared when tab closes; same-origin.

**EM answer:**  
“We’d avoid putting secrets in `localStorage` where XSS can steal them; prefer HttpOnly cookies for session tokens when our security model allows. As an EM, I’d align with security on auth storage and document it so we don’t get inconsistent patterns.”

---

### What are XSS and CSRF at a high level?

**Basics:**  
- **XSS (Cross-Site Scripting)** — Attacker injects script that runs in users’ browsers (e.g. unsanitized HTML). Can steal tokens from storage or session.  
- **CSRF (Cross-Site Request Forgery)** — Tricks a logged-in user’s browser into making a request they didn’t intend, using their cookies.

**EM answer:**  
“As an EM, I’d partner with security: sanitize/escape user content, use CSP where possible, use HttpOnly cookies and CSRF tokens for state-changing requests. I’d treat ‘paste HTML into innerHTML’ as a red flag in review unless sanitized.”

---

### What are `defer` and `async` on `<script>`, and when do you use them?

**Basics:**  
**`async`** — Script downloads in parallel and runs as soon as it’s ready (order not guaranteed vs other scripts). **`defer`** — Downloads in parallel but runs after HTML is parsed, in order. Neither blocks HTML parsing like a blocking script in `<head>` without them.

**EM answer:**  
“We’d default to `defer` for app scripts so order and DOM readiness are predictable, and use `async` for independent third-party snippets when order doesn’t matter. As an EM, I’d ensure our build or framework emits sensible script loading so we don’t hurt LCP and TTI.”

---

### Same-origin policy vs CORS (one sentence each).

**Basics:**  
**Same-origin policy** — By default, scripts can’t read responses from another origin (different scheme/host/port). **CORS** — Server can opt in with headers so the browser allows cross-origin reads from allowed origins.

**EM answer:**  
“As an EM, I’d make sure the team knows CORS is server-configured and that ‘works in Postman’ doesn’t mean the browser will allow it. We’d document staging/prod API URLs and expected CORS headers.”

---

### What is JSON, and how does the frontend use it?

**Basics:**  
**JSON** is a text format for structured data (objects and arrays). APIs often return JSON; `JSON.parse` / `JSON.stringify` convert between JSON strings and JavaScript values.

**EM answer:**  
“I’d expect engineers to validate or type API responses (TypeScript, Zod, etc.) so we don’t assume shape at runtime and crash the UI. As an EM, I’d push for shared types or schemas with backend where feasible.”

---

### Basics cheat sheet (quick reference)

| Topic | One-liner |
|--------|-----------|
| **HTML** | Structure + semantics; foundation for a11y and SEO. |
| **CSS** | Layout and look; box model, flex/grid, media queries. |
| **JS** | Behavior; async APIs; updates DOM (often via a framework). |
| **DOM** | Tree of the page; what JS ultimately changes. |
| **HTTP** | GET read, POST submit; verbs match intent. |
| **CORS** | Browser cross-origin rules; server headers must allow our app. |
| **Storage** | Cookies (server-visible), localStorage/sessionStorage (client-only). |
| **XSS / CSRF** | Inject script / forged requests; mitigate with sanitization, cookies, tokens. |

---

## 1. Core Concepts & Architecture

### How would you describe the frontend’s role in the product to a non-technical stakeholder?

**EM answer:**  
“The frontend is what users see and interact with—the UI and the experience. Our job is to make the product fast, usable, and reliable across devices and browsers. As an EM leading a full-stack team, I make sure we don’t treat frontend as an afterthought: we invest in performance, accessibility, and design consistency so the product feels cohesive and we can ship features without constant regressions.”

---

### What’s the difference between frontend and full-stack in terms of what you expect from engineers?

**EM answer:**  
“Full-stack engineers can own a feature end-to-end: API, data model, and UI. I don’t expect everyone to be a frontend expert, but I do expect: ability to build and modify UIs from designs, basic CSS/layout skills, understanding of how the browser and network affect UX, and willingness to follow our frontend standards. I’d have at least one strong frontend voice on the team (or partner with a frontend guild) so we have clear patterns, code reviews, and guardrails for everyone else.”

---

### How do you approach frontend architecture decisions (e.g. framework, state, routing)?

**EM answer:**  
“I’d involve the team and align with org standards where they exist. We’d consider: consistency with other products, hiring and onboarding, long-term support, ecosystem, and performance. I’d document the decision and the ‘why’ so we don’t revisit it every quarter. For state and routing, we’d choose patterns that scale with our app size—e.g. start simple (component state, URL params), introduce global state or more structure when we feel the pain. As an EM, I own the decision process and the trade-off discussion with product and eng leadership.”

---

### SPA vs SSR vs static—when do you recommend what?

**EM answer:**  
“**SPA**: Rich, app-like UX; good when we need heavy client-side interactivity and we can accept slower first load or invest in code-splitting. **SSR (e.g. Next, Remix)**: When we care about SEO, first-contentful paint, or dynamic per-request content. **Static**: Marketing, docs, blogs—fast and cheap. As an EM, I’d push for a clear default for our product type (e.g. ‘we’re an app, we use SPA’ or ‘we need SEO, we use SSR’) and avoid mixing patterns without a documented reason so the team and new hires have one mental model.”

---

## 2. JavaScript & TypeScript

### Why would you standardize on TypeScript for a full-stack team?

**EM answer:**  
“TypeScript catches a lot of bugs at build time, improves IDE support and refactors, and acts as living documentation. For a full-stack team, it also aligns with typed backends and API contracts. As an EM, I’d standardize on TypeScript for new frontend code and plan incremental adoption for legacy JS—with a team agreement on strictness (e.g. no `any` by default) so we get real benefit. I’d treat it as a quality and velocity investment, not optional.”

---

### How do you handle state in a typical frontend app? When do you introduce Redux/Zustand/Context?

**EM answer:**  
“We start with local component state and props. We add **Context** when we need to pass data through many layers without prop drilling. We introduce a global store (Redux, Zustand, etc.) when we have shared state that many parts of the app read or update, or when we need predictable updates (e.g. forms, multi-step flows). As an EM, I’d discourage ‘we use Redux for everything’—we’d document when to use what and review state design in tech specs so we don’t over-complicate small features or under-structure large ones.”

---

### What’s the event loop, and why does it matter for frontend?

**EM answer:**  
“JavaScript is single-threaded; the event loop runs the call stack and then pulls from the task queue (callbacks, promises, etc.). Long-running synchronous code blocks the main thread and freezes the UI. As an EM, I’d ensure the team understands: we avoid heavy sync work on the main thread, we use async/workers for heavy computation, and we measure and budget main-thread time so we hit our responsiveness and LCP targets. I’d tie this to our performance SLAs and code review norms.”

---

### How do you approach client-side vs server-side validation?

**EM answer:**  
“Client-side validation is for UX: immediate feedback, fewer round-trips. Server-side validation is for security and correctness—we never trust the client. As an EM, I’d set the rule: we always validate on the server; we add client-side validation to improve experience and reduce invalid submissions. We’d share validation rules (e.g. schema or types) between frontend and backend where possible so we don’t drift. I’d treat missing server-side validation as a security concern in review.”

---

## 2.5 State Management (Patterns for React & Full-Stack Teams)

### What's the difference between local state, lifted state, and global state?

**Basics:**  
- **Local** — `useState` inside a component; only that subtree needs it.  
- **Lifted** — State moved up to a common parent so siblings can share it via props/callbacks.  
- **Global** — Context, Redux, Zustand, etc.: many distant parts of the app read/update the same data.

**EM answer:**  
"We'd start local, lift when two children need the same source of truth, and introduce global only when prop drilling becomes painful or we need predictable updates across the app. As an EM, I'd document this ladder so full-stack engineers don't default to Redux for every form field."

---

### When do you use React Context vs a dedicated store (Redux, Zustand)?

**Basics:**  
**Context** is built-in and good for values that change infrequently (theme, auth user, feature flags). Heavy or frequent updates through Context can cause wide re-renders. **Stores** (Zustand, Redux Toolkit) offer selectors, middleware, DevTools, and more granular subscriptions.

**EM answer:**  
"I'd use Context for app-wide settings and current user; I'd use a store when we have complex client state, many subscribers, or we need time-travel/debugging. As an EM, I'd pick one store pattern org-wide if possible so onboarding is consistent."

---

### How do you think about "server state" vs "client state"?

**Basics:**  
**Server state** — Data that lives on the API (lists, profiles, configs): caching, refetching, staleness, loading/error matter. Libraries like **TanStack Query (React Query)**, **SWR**, or **RTK Query** handle caching, deduping, and background refresh. **Client state** — UI-only: modal open, selected tab, draft input before save.

**EM answer:**  
"I'd separate concerns: don't put API cache in Redux by default if a data-fetching library fits. As an EM, I'd standardize on one approach for server state so we don't reinvent caching and race-condition handling in every feature."

---

### Can the URL be part of your state management strategy?

**Basics:**  
Yes—**query params and path** are shareable, bookmarkable, and work with back/forward. Good for filters, pagination, selected views. Trade-off: not everything belongs in the URL (sensitive data, huge payloads).

**EM answer:**  
"I'd push for URL state for anything users should share or refresh without losing context. As an EM, I'd align with product on deep-linking and ensure we don't hide critical UX state only in memory."

---

## 3. React / Framework (adjust for Vue, Angular, etc.)

### React basics: What is a component?

**Basics:**  
A **component** is a reusable piece of UI: a function (or class) that returns elements. It encapsulates structure, behavior, and optionally local state. **Composition** — building pages from smaller components—is preferred over inheritance.

**EM answer:**  
"I'd expect the team to split UI into small, testable components with clear props. As an EM, I'd watch for 'god components' in review and encourage refactors when files become hard to reason about."

---

### What are props vs state in React?

**Basics:**  
**Props** — Inputs from parent; read-only in the child (don't mutate). **State** — Data owned by the component that can change over time and triggers re-renders when updated via `setState` / setters from hooks.

**EM answer:**  
"Confusing props and state causes bugs (mutating props, stale state). As an EM, I'd enforce immutability and clear data flow in review, and use TypeScript for prop contracts."

---

### What are common hooks and when are they used? (Overview.)

**Basics:**  
- **`useState`** — Local state.  
- **`useEffect`** — Side effects (fetch, subscriptions, syncing with external systems).  
- **`useMemo` / `useCallback`** — Memoize values/functions to avoid expensive recompute or unnecessary child re-renders (use when measured need, not everywhere).  
- **`useRef`** — Mutable box that doesn't trigger re-render; DOM refs, timers, latest value in callbacks.  
- **`useContext`** — Read a Context value.  
- **`useReducer`** — State with multiple sub-values or complex transitions (similar to a lightweight reducer pattern).

**EM answer:**  
"I don't need everyone to optimize prematurely, but I need them to know **when** each hook fits. As an EM, I'd use lint rules (exhaustive-deps) and review to prevent effect misuse and hook spaghetti."

---

### Why do lists need stable `key` props?

**Basics:**  
**`key`** helps React match items across renders. Stable keys (IDs) preserve identity and state; using **array index** as key can cause wrong updates or lost focus when the list reorders or items are inserted/removed.

**EM answer:**  
"I'd treat wrong keys as a review red flag for dynamic lists—they cause subtle UI bugs. As an EM, I'd document: prefer business IDs, avoid index keys unless list is static and append-only."

---

### Controlled vs uncontrolled inputs—what's the difference?

**Basics:**  
**Controlled** — React state is the source of truth (`value` + `onChange`). **Uncontrolled** — DOM holds the value (`defaultValue`, `ref` to read). Controlled gives single source of truth and easier validation; uncontrolled can be simpler for basic forms.

**EM answer:**  
"We'd default to controlled for forms that need validation, multi-step flows, or instant feedback. As an EM, I'd be consistent within a product area so we don't mix patterns randomly."

---

### What causes a React component to re-render?

**Basics:**  
Parent re-render (unless memoized children), **state** update in the component, **Context** value change for consumers, **props** change. Hooks don't stop parent-driven re-renders by themselves.

**EM answer:**  
"Unnecessary re-renders can hurt performance on large trees. As an EM, I'd encourage measuring first (React DevTools Profiler), then `React.memo`, `useMemo`, `useCallback` where justified—not blanket optimization. I'd tie this to our performance culture, not heroics."

---

### What is React Strict Mode?

**Basics:**  
**Strict Mode** (development) double-invokes some lifecycles/effects to surface unsafe side effects and deprecated APIs. It helps find bugs early; it's not a production performance hit in the same way.

**EM answer:**  
"I'd keep Strict Mode on in dev and fix double-effect issues properly (idempotent effects, cleanup). As an EM, I'd treat 'works only if effect runs once' as a smell—effects should tolerate strict dev behavior."

---

### What’s the virtual DOM, and why do we care?

**EM answer:**  
“The virtual DOM is an in-memory representation of the UI. The framework diffs it with the previous version and updates the real DOM only where needed, which simplifies programming and often improves performance versus manual DOM updates. As an EM, I’d want the team to understand that it’s not free—re-renders and diffing have cost, so we still need to think about when components re-render and use patterns like memoization or keys when it matters. I’d connect this to our performance budgets and profiling habits.”

---

### When do you use useEffect, and what pitfalls do you watch for?

**EM answer:**  
“We use **useEffect** for side effects that depend on props/state: data fetch, subscriptions, DOM updates. Pitfalls: missing or wrong dependency arrays (stale closures, infinite loops), doing too much in one effect, or using effects for derived state when we should use useMemo/useState. As an EM, I’d encourage clear guidelines (e.g. one concern per effect, dependency array must be correct) and use code review and lint rules to catch common mistakes so junior and full-stack folks don’t introduce subtle bugs.”

---

### How do you structure components for reusability and maintainability?

**EM answer:**  
“We aim for single responsibility, clear props interface, and composition over configuration where it fits. We’d have a small set of patterns: presentational vs container (or feature components), shared UI in a design system or component library, and domain-specific components in feature folders. As an EM, I’d document our folder and component conventions and refactor when we see duplication or unclear ownership. I’d also protect time for cleaning up components when we add features so we don’t accumulate one-off UIs.”

---

### What’s your take on client components vs server components (e.g. React Server Components)?

**EM answer:**  
“Server components render on the server and send less JS to the client—good for performance and for parts of the page that don’t need interactivity. Client components are for interactivity, hooks, browser APIs. As an EM, I’d push the default: use server components where possible, and use client components only where we need state, effects, or browser APIs. I’d make this a team standard and review so we don’t accidentally make everything client-side and lose the benefits of the framework.”

---

## 4. CSS & Layout

### How do you keep CSS maintainable in a full-stack team?

**EM answer:**  
“We’d standardize on one approach: e.g. CSS Modules, Tailwind, or a design-system utility set, so we don’t have five different styles in one codebase. We’d have naming conventions (BEM-like or component-scoped) and avoid global styles except for design tokens. As an EM, I’d own the standard and the decision to adopt a design system or Tailwind so we get consistency and onboarding is straightforward. I’d also use lint/PR review to catch inline styles or one-off classes that should be shared.”

---

### How do you approach responsive design and cross-browser support?

**EM answer:**  
“We’d define breakpoints and a mobile-first or desktop-first approach and stick to it. We’d test on real devices or emulators for the browsers we support (e.g. last 2 versions of Chrome, Safari, Firefox, and key mobile). As an EM, I’d align with product on which browsers we support and document it; I’d push for design-system components that are responsive by default so feature work doesn’t re-solve layout every time. I’d also tie browser support to our testing and CI so we don’t regress.”

---

### What are design tokens, and why would you use them?

**EM answer:**  
“Design tokens are named values for colors, spacing, typography, etc., shared between design and code. They give us one source of truth and make theming and consistency easier. As an EM, I’d adopt tokens when we have a design system or multiple products/themes; I’d involve design early so tokens map to their language. I’d treat them as the standard for spacing and colors so we don’t have magic numbers and random hex codes across the app.”

---
## 4.5 Browser Internals & Behavior (EM-Level)

### What is reflow vs repaint, and why should the team care?

**Basics:**  
**Reflow (layout)** — Browser recalculates geometry (positions, sizes) when something changes that affects layout. **Repaint** — Redrawing pixels (e.g. color change) without full layout. Reflow is usually more expensive; forcing synchronous layout (read layout after write in a tight loop) hurts performance.

**EM answer:**  
"I'd want the team to know that thrashing the DOM or reading `offsetHeight` in a loop after writes causes jank. As an EM, I'd connect this to our performance reviews and to batching DOM updates—often the framework handles it, but custom code and third-party widgets can still cause issues."

---

### What are the main browser storage surfaces besides cookies?

**Basics:**  
**IndexedDB** — Larger structured client-side storage (offline, PWAs). **Cache API** — Used with service workers for caching network responses. **Session** — Often server-side; client may only hold a session id in a cookie.

**EM answer:**  
"I'd align with security and product on what we store where—especially for offline or PWA features. As an EM, I'd ensure we don't put PII in client stores without encryption and a clear retention story."

---

### What is a Content Security Policy (CSP), at a high level?

**Basics:**  
**CSP** is an HTTP header that tells the browser which sources are allowed for scripts, styles, images, etc. It reduces XSS impact by blocking inline scripts or untrusted origins.

**EM answer:**  
"I'd partner with security and infra to roll out CSP in report-only first, then enforce. As an EM, I'd expect some build/pipeline work (nonces/hashes for inline) so we don't break the app—I'd treat it as a cross-team initiative, not a frontend-only task."

---

## 5. Performance

### What metrics do you care about for frontend performance, and how do you track them?

**EM answer:**  
“I care about **LCP** (loading), **FID/INP** (interactivity), **CLS** (visual stability), and sometimes **TTFB**. We’d use Real User Monitoring (RUM) and possibly Lighthouse in CI. As an EM, I’d set SLOs (e.g. LCP &lt; 2.5s on p75) and alert when we regress. I’d tie performance to our definition of done for major features and reserve time for fixing regressions so we don’t let the product slow down over time.”

---

### How do you improve initial load time and time to interactive?

**EM answer:**  
“We’d focus on: **code splitting** and lazy loading so we don’t load everything upfront, **asset optimization** (minify, compress, modern formats for images), **critical path** (above-the-fold CSS/JS first), and **caching**. We’d measure bundle size and set budgets. As an EM, I’d make performance a standing agenda item: we’d profile before/after big features and fix regressions in the same sprint. I’d also ensure we have the tooling (e.g. bundle analyzer, CI checks) so the team can see impact.”

---

### What’s your approach to frontend performance budgets?

**EM answer:**  
“We’d set budgets for JS bundle size (total and per-route if we have routing), and optionally for CSS and images. CI would fail or warn when a PR exceeds the budget. As an EM, I’d own the initial numbers (based on current baseline and target LCP) and the process for updating them when we have a justified need. I’d treat budget breaches as a discussion, not a hard block—but we’d require a reason and a plan to compensate (e.g. split another chunk) so we don’t grow unbounded.”

---

## 6. Accessibility (A11y)

### Why does accessibility matter to you as an EM, and how do you prioritize it?

**EM answer:**  
“Accessibility is the right thing to do and often a legal requirement. It also improves usability for everyone (keyboard, clarity, structure). As an EM, I’d treat a11y as non-negotiable: we’d include it in our definition of done, use lint and automated checks in CI, and do manual testing (keyboard, screen reader) for critical flows. I’d allocate time for training and for fixing a11y debt so we don’t pile it up. I’d also align with product and legal on our commitment and roadmap.”

---

### What are the main things you’d ensure the team does for accessibility?

**EM answer:**  
“Semantic HTML (headings, landmarks, buttons vs divs), keyboard navigation and focus management, sufficient color contrast and not relying on color alone, alt text for images, and ARIA where needed without overusing it. We’d use axe or similar in CI and fix critical/serious issues before ship. As an EM, I’d make these part of our component and PR standards and would support the team with access to a11y tools and, if possible, expert review for high-traffic or regulated flows.”

---

### How do you handle accessibility in a team where not everyone is a frontend specialist?

**EM answer:**  
“We’d provide a short checklist (semantic HTML, keyboard, focus, contrast, alt text) and rely on a shared component library that’s built accessibly by default. We’d run automated checks in CI and in code review. I’d assign an a11y champion (or rotate) to own guidelines and answer questions, and we’d do a lightweight audit for major releases. As an EM, I’d frame it as ‘we all own it; the system and components make it easier’ so it’s not only on one person.”

---

## 7. Testing

### What’s your testing strategy for the frontend (unit, integration, E2E)?

**EM answer:**  
“We’d aim for: **unit tests** for utilities and pure logic; **component/integration tests** (e.g. React Testing Library) for critical UI and user flows; **E2E tests** for a small set of happy paths and critical journeys. I’d avoid testing implementation details and focus on behavior. As an EM, I’d set expectations: e.g. critical paths must have coverage, new features include tests, and we don’t let E2E suite become so slow or flaky that we ignore it. I’d balance coverage with maintainability and feedback time.”

---

### How do you prevent the UI from breaking when full-stack engineers touch frontend code?

**EM answer:**  
“Through code review (someone with frontend context), shared patterns and a component library, and automated tests for critical flows. We’d run visual regression or snapshot tests selectively where they add value. As an EM, I’d ensure we have a clear owner for frontend quality (e.g. a tech lead or guild) and that PR expectations include ‘does this follow our patterns and tests?’ I’d also encourage pairing when a backend-heavy engineer is doing a large UI change so we spread knowledge and catch issues early.”

---

### How do you deal with flaky E2E tests?

**EM answer:**  
“We’d fix or quarantine flaky tests quickly so the suite stays trustworthy. We’d prefer stable selectors (e.g. data-testid, role-based) and explicit waits over arbitrary timeouts. We’d run E2E in a consistent environment and keep the set small enough to maintain. As an EM, I’d treat flakiness as a priority: we’d track flaky rate and allocate time to fix or remove bad tests. I’d avoid adding more E2E tests until we’ve improved stability so we don’t compound the problem.”

---

## 8. Tooling, Build, Webpack/Vite, CI/CD, AWS Hosting & DX

### How do you balance “latest and greatest” with stability in frontend tooling?

**EM answer:**  
“We’d follow a predictable upgrade cadence (e.g. dependency updates quarterly) and evaluate new tools or major versions when we have a clear benefit (security, performance, DX) or pain. We’d avoid adopting a new framework or build system on a whim. As an EM, I’d own the bar for adoption: we’d document the rationale and get team buy-in. I’d also reserve time for upgrades and migrations so we don’t accumulate tech debt and risky big-bang upgrades.”

---

### What do you expect from the frontend build and dev experience?

**EM answer:**  
“Fast local dev (HMR, quick feedback), clear errors, and a build that’s reproducible and runs in CI. We’d have lint and format on save or pre-commit so style is consistent. As an EM, I’d treat DX as a productivity lever: if the team complains about slow builds or confusing tooling, we’d invest in fixing it. I’d also ensure onboarding docs cover how to run and debug the frontend so new and full-stack folks can contribute without friction.”

---

### How do you approach design-system or component-library ownership?

**EM answer:**  
“We’d have a clear owner (team or guild) for the design system and a process for proposing and adding components. We’d align with design on tokens and components and document usage. As an EM, I’d ensure the full-stack team uses the system by default and escalates when something is missing—rather than building one-off UIs. I’d also protect time for the system’s maintainers so it stays consistent and documented as the product grows.”

---

### What does a bundler like Webpack (or Vite) do for the frontend?

**Basics:**  
A **bundler** takes modules (JS/TS, CSS, assets), resolves imports, **transpiles** (e.g. TypeScript, JSX), **bundles** into optimized chunks, and can **code-split**, **tree-shake** dead code, and apply **minification** for production. **Vite** uses esbuild for dev (fast cold start) and Rollup for production builds—same problems, different trade-offs.

**EM answer:**  
"As an EM, I care that we have one blessed build pipeline, documented env vars, and CI that runs the same `build` as local. I'd compare Webpack vs Vite on team familiarity, plugin ecosystem, and build time—then document the choice so we're not re-litigating it per project."

---

### What would you run in CI for a frontend repo?

**Basics:**  
Typical stages: **install** (lockfile-pinned deps), **lint** (ESLint), **format check** (Prettier), **typecheck** (`tsc --noEmit`), **unit/integration tests**, **production build** (fail on errors/warnings policy), optional **bundle size** / **Lighthouse CI** / **a11y** (axe). **E2E** may run on main, nightly, or pre-merge depending on cost and flakiness.

**EM answer:**  
"I'd optimize for fast feedback on PRs (lint, types, unit tests, build) and run heavier E2E on a schedule or merge queue if needed. As an EM, I'd own the rule: red main is unacceptable—we fix or revert quickly—and I'd track flaky tests as engineering work, not noise."

---

### How would you host a static frontend on AWS?

**Basics:**  
Common pattern: build artifacts (HTML, JS, CSS) to **S3** with **CloudFront** CDN using **OAC/OAI** so the bucket isn't publicly listable. **CloudFront** gives HTTPS, edge caching, custom domains, and (optionally) **WAF**. **Route 53** for DNS. For **SPA** client-side routing, configure **custom error responses** so CloudFront returns `index.html` for 404 on document routes (carefully—don't break real 404s for static assets). Alternatives: **AWS Amplify Hosting** (CI + CDN in one), **ECS/Fargate** or **Elastic Beanstalk** if we need a **Node server** for SSR (Next.js, etc.).

**EM answer:**  
"I'd choose static + CDN for a pure SPA; SSR needs compute (Lambda@Edge, ECS, etc.). As an EM, I'd document environments (dev/stage/prod), who owns CloudFront invalidations, and how we inject config (build-time vs runtime) so releases are predictable and secure."

---

### How do environment variables work for frontend builds?

**Basics:**  
Values baked in at **build time** become public in the JS bundle—**never put secrets** in `REACT_APP_*` / `NEXT_PUBLIC_*` style vars. Use them for **non-secret** config (API base URL, public feature flag keys). True secrets stay on the server.

**EM answer:**  
"I'd run a security primer for the team: anything in the client bundle is visible. As an EM, I'd block secrets in frontend env in review and use secret scanning in CI where possible."

---

### How does blue/green or canary relate to frontend deploys?

**Basics:**  
**Blue/green** — Two full stacks; switch traffic when ready. **Canary** — Gradual traffic shift. For static sites, use **multiple S3 prefixes or origins** with weighted routing, or platform-native canary (Amplify). **Content-hashed filenames** help caching: users get matching JS/CSS for each deploy.

**EM answer:**  
"I'd ensure hashed assets so we don't get mismatched bundles after deploy. As an EM, I'd align with SRE on rollback: keep last N builds or use versioned prefixes so we can revert quickly."

---

## 9. Team & Process (EM-Focused)

### How do you ensure frontend quality when your team is mostly full-stack?

**EM answer:**  
“By setting standards (patterns, TypeScript, tests, a11y), having at least one strong frontend voice for reviews and guidance, and using tooling (lint, typecheck, tests, performance budgets) that enforce a baseline. I’d also do lightweight design/UX review for major changes so we don’t ship inconsistent or hard-to-use UIs. As an EM, I’d own the definition of ‘good enough’ for frontend and iterate on it with the team so we improve over time without blocking delivery.”

---

### How do you hire or grow frontend capability in a full-stack team?

**EM answer:**  
“When hiring, I’d look for full-stack candidates who are willing to work on frontend and have some experience; I’d also consider a dedicated frontend hire if we’re frontend-heavy or building a design system. For growth, I’d encourage ownership of frontend initiatives (e.g. performance, a11y, component library), pair with stronger frontend folks, and support training or conference time. As an EM, I’d make frontend a visible and valued part of the team’s work so people don’t avoid it.”

---

### How do you work with design and product on frontend scope and feasibility?

**EM answer:**  
“We’d involve eng early in design so we can flag feasibility, performance, or a11y concerns. We’d agree on what’s in scope for an MVP and what we’ll iterate on. I’d push for reusable components and design tokens so we’re not building one-off screens every time. As an EM, I’d facilitate the conversation between design and the team, set expectations on handoff (e.g. specs, assets, states), and escalate when scope or timeline doesn’t match our capacity so we don’t over-promise or cut quality silently.”

---

### What would you do if the frontend had become a mess of tech debt and inconsistency?

**EM answer:**  
“I’d assess the pain: performance, bugs, velocity, onboarding. Then I’d prioritize: fix what blocks delivery or causes incidents first; then tackle consistency (e.g. one styling approach, shared components). I’d create a small roadmap and get buy-in from product for some dedicated time (e.g. 20% per sprint or a focused sprint). I’d avoid big rewrites unless necessary; I’d prefer incremental improvement and setting new standards for new code. As an EM, I’d own the narrative with leadership so they understand why we’re investing and what we’ll get.”

---

## 10. Integrated View: Security, Accessibility, Performance & Usability

### How do you balance security, a11y, performance, and usability together?

**EM answer:**  
"They're not competing if we build habits early: accessible semantic HTML often helps SEO and keyboard UX; performance (fast loads, responsive UI) is part of usability; security (no XSS, safe auth) prevents incidents that destroy trust. As an EM, I'd use a short **definition of done** checklist per feature: loading/error/empty states, basic keyboard path, no secrets in client, and a quick performance sniff test for big changes. I'd escalate trade-offs to product when we must choose (e.g. third-party script vs strict CSP)."

---

### Frontend security beyond XSS—what do you watch for?

**Basics:**  
**Dependency risk** — `npm audit`, Dependabot, lockfiles, supply-chain attacks. **Misconfigured CORS** — overly permissive `*`. **Leaking tokens** in URLs, logs, or client storage. **Clickjacking** — `X-Frame-Options` / CSP `frame-ancestors`. **Mixed content** — HTTPS page loading HTTP assets.

**EM answer:**  
"I'd make dependency updates and audit response part of our operating rhythm—not optional. As an EM, I'd partner with security for periodic reviews of auth flows and third-party scripts (analytics, chat widgets) because they expand the attack surface."

---

### What does usability mean to you on top of 'it works'?

**Basics:**  
Clear **labels and errors** (what went wrong, what to do next), **loading** and **empty** states, **consistent patterns** (same action, same control), **forgiving flows** (undo, confirm destructive actions), **mobile** touch targets and readability.

**EM answer:**  
"I'd involve design and PM in acceptance criteria for states, not just the happy path. As an EM, I'd reject 'works on my machine' for UX—we'd do a quick hallway test or design review for high-impact flows."

---

### How do you prevent performance and a11y from being 'Phase 2'?

**EM answer:**  
"By measuring in CI (budgets, axe), setting team norms in PR template, and allocating a slice of every sprint for fixes. As an EM, I'd show leadership data: slow pages and a11y gaps correlate with support tickets and churn. I'd avoid big-bang audits that never ship—small continuous improvements win."

---

## 11. Logic & Code Review (EM Perspective)

### How do you approach code review as an EM—do you review every line?

**EM answer:**  
"I focus on **risk, architecture, and team growth**: security-sensitive paths, API contracts, state and data flow, error handling, tests, and whether the change matches our standards. I don't need to nitpick style if lint/format cover it. I delegate deep frontend review to strong ICs but spot-check enough to stay credible and to catch gaps in our process."

---

### What do you look for in a frontend PR specifically?

**EM answer:**  
"**Correctness** — edge cases, loading/error states, race conditions in async UI. **Accessibility** — buttons vs divs, focus, labels. **Performance** — large dependencies, unnecessary re-renders only if justified by profiler, N+1 API calls from the client. **Security** — unsanitized HTML, secrets, unsafe `dangerouslySetInnerHTML`. **Maintainability** — component size, naming, duplication vs design system. **Tests** — meaningful coverage for logic and critical UI paths."

---

### How do you give feedback on someone's logic or approach without demotivating them?

**EM answer:**  
"I'd separate **person from code**, ask questions ('help me understand why we chose X'), and suggest alternatives as options. I'd praise specific good decisions and be direct on blockers (security, data loss). As an EM, I'd follow up 1:1 if a pattern repeats so we coach in private, not only in PR threads."

---

### When do you block a merge vs approve with follow-ups?

**EM answer:**  
"I'd **block** on security issues, data corruption risk, missing tests for critical paths, or violations of agreed architecture. I'd **approve with follow-ups** for minor refactors, tech debt tickets we'd file immediately, or style nits already tracked. As an EM, I'd be consistent so the team knows the bar and doesn't negotiate every time."

---

### How do you scale review when the team grows?

**EM answer:**  
"**CODEOWNERS** for sensitive areas, **balanced review load** across seniors, **PR templates** with checklists, and **pairing** for large changes. I'd watch for review latency and burnout—if reviews bottleneck, we add reviewers or reduce WIP. As an EM, I'd track time to first review and PR size and coach on smaller PRs."

---

### How do you review 'business logic' that lives in the frontend?

**EM answer:**  
"I'd ask: is this logic **authoritative**? If yes, it must be enforced on the server—we treat client rules as UX only. I'd look for clear naming, unit tests for pure functions, and separation from UI so we can test rules without rendering. As an EM, I'd escalate if product pressure pushes financial or compliance logic only to the client."

---


## 12. Quick Reference (EM Cheat Sheet)

| Topic | EM one-liner |
|-------|---------------|
| **Basics (§0)** | HTML structure, CSS presentation, JS behavior; DOM, HTTP, CORS, storage, XSS/CSRF—see full section for Q&A. |
| **State (§2.5)** | Local → lifted → global; Context vs store; server state (React Query/SWR) vs client; URL as state. |
| **React basics (§3)** | Components, props/state, hooks overview, keys, controlled inputs, re-renders, Strict Mode. |
| **Browser (§4.5)** | Reflow/repaint, CSP, IndexedDB/Cache API. |
| **Build & AWS (§8)** | Webpack/Vite, CI stages, S3+CloudFront, env vars (no secrets in client). |
| **Quality pillars (§10)** | Security + a11y + performance + usability in DoD; dependency hygiene. |
| **Review (§11)** | Risk-based review, frontend PR checklist, block vs follow-up, scaling review. |
| **Frontend ownership** | Everyone can contribute; we have standards, review, and at least one strong frontend voice. |
| **Architecture** | Document framework and patterns; align with org; avoid random choices per feature. |
| **State** | Start local; add global store when shared state or predictability justifies it. |
| **Performance** | Set SLOs (e.g. LCP), budgets, and measure; fix regressions as part of delivery. |
| **Accessibility** | Non-negotiable; lint + manual checks; design system and checklist for the team. |
| **Testing** | Unit for logic; component/integration for key UI; E2E for critical paths; keep suite stable. |
| **CSS** | One approach (e.g. Tailwind or design system); tokens and conventions; avoid one-off styles. |
| **Design system** | Clear ownership; use by default; full-stack team contributes within the system. |
| **Full-stack + frontend** | Standards, tooling, and review so non-specialists can ship quality UI safely. |

---

*Use this doc to practice answers out loud and plug in your own examples (e.g. “In my last role we had a lot of full-stack engineers; we introduced X and saw Y improvement”).*
