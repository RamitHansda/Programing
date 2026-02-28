# Design Patterns: When to Use & More Examples

Companion to [LLD Problems by Design Pattern](LLD_PROBLEMS_BY_DESIGN_PATTERN.md). For each pattern: **when to use it** and **multiple examples** so you can recognize the right pattern in interviews and production.

---

## Creational Patterns

### 1. Singleton

**Use when:**
- There must be **exactly one instance** of a class for the whole process (or a well-defined scope like “per JVM”).
- That instance is a **single point of access** (e.g. config, pool, logger).
- You want to **avoid passing the same object everywhere** or creating duplicate resources.

**Don’t overuse:** Prefer dependency injection when testability and flexibility matter; Singleton can make testing and multi-tenant setups harder.

| Example | Why Singleton fits |
|--------|----------------------|
| **Database connection pool** | One pool per app; all threads get/release from the same pool. |
| **Application configuration** | Single source of truth for env-based config (loaded once). |
| **Logger / audit writer** | One logger instance; all components write to the same sink. |
| **Cache manager** | One global cache (e.g. in-memory or L1) so all callers share it. |
| **Thread pool / scheduler** | One shared executor so you don’t oversubscribe the system. |
| **Service locator** (use sparingly) | One registry that resolves dependencies; often replaced by DI. |

---

### 2. Factory Method

**Use when:**
- You need to **create one of several “product” types** (exporters, parsers, handlers) **without** the caller depending on concrete classes.
- **Creation logic varies** (e.g. by format, protocol, or environment) and you want to add new product types without changing client code.
- The **caller works with an interface**; “who creates the concrete object” is decided in one place (factory or subclass).

| Example | Why Factory Method fits |
|--------|--------------------------|
| **Document exporter** (PDF/Word/MD) | Caller asks for “export as X”; factory returns the right exporter. |
| **Parser factory** (JSON/XML/CSV) | `ParserFactory.getParser(format)` returns the right parser. |
| **Notification sender** (Email/SMS/Push) | `NotificationFactory.create(channel)` returns channel-specific sender. |
| **Database connection** (MySQL/Postgres/Mock) | Test vs prod: factory returns real or in-memory connection. |
| **Serialization** (JSON/Protobuf/Avro) | `SerializerFactory.forFormat(format)` returns the right serializer. |
| **Handler per request type** | API router: factory returns the handler for “create order” vs “get balance”. |

---

### 3. Abstract Factory

**Use when:**
- You need a **family of related products** that must **work together** (e.g. all widgets from the same theme, all DAOs for the same DB).
- You want to **swap the whole family** (e.g. switch theme or persistence implementation) without changing call sites.
- **Consistency** matters: you must not mix products from different families (e.g. Light button + Dark textbox).

| Example | Why Abstract Factory fits |
|--------|----------------------------|
| **UI kit (Light/Dark theme)** | One factory per theme; all Button/TextBox/Checkbox match. |
| **Cross-platform UI** (Web/iOS/Android) | One factory per platform; all controls are native for that platform. |
| **Persistence layer** (SQL vs NoSQL)** | One factory gives UserRepo + OrderRepo for the same backend. |
| **Look-and-feel** (Material/Bootstrap/Custom) | One factory per L&F; all components share the same style. |
| **Document builder** (Letter/Report/Slide) | One factory produces header, body, footer types that fit one document type. |

---

### 4. Builder

**Use when:**
- The object has **many fields**, several of which are **optional**, and you want to avoid telescoping constructors or unreadable `new X(a, b, null, null, 0, …)`.
- You want **step-by-step construction** and a **fluent API** (e.g. `.withName().withAddress().build()`).
- The same construction process should support **different representations** (e.g. same builder, different validation or defaults).

| Example | Why Builder fits |
|--------|-------------------|
| **SQL query builder** | `.select().from().where().orderBy().limit()`; all optional except from. |
| **HTTP request** | `.url().method().header().body().build()`. |
| **Alert/notification** | `.title().body().priority().channels().build()`. |
| **Test data / fixture** | `.withUser().withOrders(3).withAddress().build()` with sensible defaults. |
| **Config object** | Many optional settings; builder with defaults and validation in `build()`. |
| **Document model** | Rich document with optional sections, metadata, attachments. |

---

### 5. Prototype

**Use when:**
- Creating a **new instance from scratch is expensive** (disk, network, heavy computation) but **copying an existing instance is cheap**.
| You have **templates** (e.g. default enemy, default form) and want many **slightly different copies** (e.g. change position only).
- You want to **avoid a deep dependency on concrete classes** for creation; clone from a prototype instead.

| Example | Why Prototype fits |
|--------|---------------------|
| **Game entities** (enemies, trees) | Clone template; override position/health. Avoid reloading sprites. |
| **Form templates** | Clone “default application form”; override applicant id and date. |
| **Document templates** | Clone contract template; fill in party names and dates. |
| **Caching** | Clone cached “base” response; update only the varying part. |
| **UI components** | Clone a pre-configured widget; change label and callback. |

---

## Structural Patterns

### 6. Adapter

**Use when:**
- You need to **use an existing class or library** whose interface **doesn’t match** what your code expects.
- You **can’t or don’t want to change** the existing code (third-party SDK, legacy module).
- You want **one consistent interface** in your app; the adapter translates to/from the other interface.

| Example | Why Adapter fits |
|--------|-------------------|
| **Third-party payment SDK** | Your code: `PaymentProcessor.pay(...)`; SDK: `initiatePayment(Request)`. Adapter maps between them. |
| **Legacy REST API** | Your code expects `UserService.getUser(id)`; legacy returns XML and different field names. |
| **External message queue** | Your code expects `MessageBus.publish(topic, event)`; vendor API is `send(Queue, Message)`. |
| **Metrics library** | Your code: `Metrics.record(name, value)`; library: `statsd.increment(key)`. |
| **File formats** | Your code works with “document”; adapter reads/writes legacy binary format. |

---

### 7. Bridge

**Use when:**
- You have an **abstraction** (e.g. “remote control”, “notification”) and **multiple implementations** (e.g. TV, AC; or Email, SMS).
- You want **abstraction and implementation to vary independently** so you don’t get N×M classes (every remote × every device).
- You expect to **add new abstractions** (new remote) or **new implementations** (new device) without touching the other side.

| Example | Why Bridge fits |
|--------|-------------------|
| **Remote control + devices** | Remote (abstraction) + TV/AC/Speaker (implementations); add device without new remote class. |
| **Notification** | “Notifier” abstraction + Email/SMS/Push implementations. |
| **Storage** | “Repository” abstraction + S3/Local/Encrypted implementations. |
| **Rendering** | “Shape” abstraction + Vector/Raster rendering implementations. |
| **Persistence** | “DAO” abstraction + JDBC/NoSQL implementations. |

---

### 8. Composite

**Use when:**
- You have a **tree structure** where **leaves and containers** (e.g. file vs directory) should be **treated the same** by callers.
- Operations are **recursive** (e.g. size of directory = sum of children’s sizes; find in directory = search in children).
- You want **uniform handling** so client code doesn’t branch on “is this a file or a directory?”.

| Example | Why Composite fits |
|--------|---------------------|
| **File system** | File and Directory both have `getSize()`, `find()`; directory delegates to children. |
| **UI layout** | Panel contains widgets or nested panels; all have `render()`, `getBounds()`. |
| **Org chart** | Employee (leaf) and Department (composite) both support `getTotalHeadcount()`. |
| **Expression tree** | Literal and Expression both have `evaluate()`; expression delegates to sub-expressions. |
| **Menu / navigation** | MenuItem (leaf) and SubMenu (composite) both support `render()`, `execute()`. |

---

### 9. Decorator

**Use when:**
- You want to **add behavior** (logging, retry, compression) **without subclassing** and without changing existing classes.
- You want to **compose behavior** by wrapping: e.g. “buffered + logging + decompressing” stream.
- **Order of wrappers** can matter and you want to **stack** multiple responsibilities dynamically.

| Example | Why Decorator fits |
|--------|---------------------|
| **Stream pipeline** | Base stream + Buffering + Compression + Logging; stack in any order. |
| **HTTP client** | Base client + Retry + Logging + Auth decorators. |
| **Coffee shop** | Base beverage + Milk + Whip + Syrup; each adds cost and description. |
| **Permissions** | Base service + ReadOnly decorator (reject writes) or Audit decorator (log calls). |
| **Caching** | Base repository + Cache decorator (read-through cache around DB). |

---

### 10. Facade

**Use when:**
- A **use case** involves **several subsystems** (inventory, payment, shipping) and you want **one simple entry point** for that use case.
| Callers should **not need to know** the order of calls or the details of each subsystem.
- You want to **encapsulate coordination** and **simplify the API** (e.g. one method “fulfill order” instead of five).

| Example | Why Facade fits |
|--------|------------------|
| **Order fulfillment** | One method coordinates: reserve inventory → charge payment → ship → notify. |
| **User onboarding** | One method: create user → send verification → add to default workspace → send welcome email. |
| **Report generation** | One method: fetch data → run analytics → render PDF → upload to storage → send link. |
| **Refund flow** | One method: reverse payment → restore inventory → send notification → update ledger. |
| **Compiler front-end** | One facade: lex → parse → resolve → type-check; callers just “compile(source)”. |

---

### 11. Flyweight

**Use when:**
- You have **many instances** that share **a lot of identical state** (intrinsic) and only differ in a **small amount** (extrinsic, e.g. position).
- **Memory or creation cost** is a concern (e.g. 100k tree instances).
- You can **separate** “shared per type” data from “per instance” data and pass extrinsic data at use time.

| Example | Why Flyweight fits |
|--------|---------------------|
| **Forest / tree rendering** | One TreeType per species (mesh, texture); each Tree has only position, scale. |
| **Character glyphs** | One glyph object per character; each occurrence has position, size, color. |
| **Particle system** | One “particle type” (sprite, physics params); each particle has position, velocity, life. |
| **Tile map** | One tile type (image, walkable); each cell has (x, y) and type reference. |
| **Formatting (e.g. rich text)** | Shared style object (font, color); each run has start index, length, style ref. |

---

### 12. Proxy

**Use when:**
- You want **lazy loading**: create the real object only when it’s first used (e.g. large image, big report).
- You want **access control**: check permissions or rate limits before delegating to the real object.
- You want **extra behavior** (logging, metrics, caching) around the real object without changing it.

| Example | Why Proxy fits |
|--------|-----------------|
| **Lazy report/image** | Proxy implements same interface; loads from disk/network on first method call. |
| **Access control** | Proxy checks “can this user call this method?” before delegating. |
| **Caching** | Proxy: return cached value if present, else call real service and cache. |
| **Remote / RPC** | Local proxy implements interface; forwards to remote service (stub). |
| **Audit** | Proxy logs every call (who, when, args) then delegates. |

---

## Behavioral Patterns

### 13. Chain of Responsibility

**Use when:**
- A **request** (e.g. log message, HTTP request) can be **handled by one of several handlers** and you don’t want the sender to know which one.
- You want to **pipeline** handlers (each can do something and/or pass to next) and **change the chain** at runtime (add/remove/reorder).
- **Multiple handlers** might act on the same request (e.g. log to file and send errors to Slack).

| Example | Why CoR fits |
|--------|---------------|
| **Logging pipeline** | Console → File → ErrorAlert; each handler can handle and/or pass on. |
| **Request middleware** | Auth → RateLimit → Logging → Handler; any can short-circuit. |
| **Event handling** | Click event: handler 1 (tooltip) → handler 2 (navigate) → handler 3 (analytics). |
| **Approval workflow** | Request passes through Manager → Director → VP until someone approves. |
| **Input validation** | Validator chain: not null → format → business rules; first failure stops or continues. |

---

### 14. Command

**Use when:**
- You need **undo/redo**: actions are encapsulated so you can reverse them.
- You want to **queue**, **schedule**, or **log** actions (e.g. job queue, macro, audit trail).
- You want to **decouple** “what to do” from “who invokes it” (e.g. button click vs menu vs shortcut).

| Example | Why Command fits |
|--------|-------------------|
| **Text editor undo/redo** | Insert/Delete/Bold as commands with `execute()` and `undo()`. |
| **Job queue** | Each job is a command; worker executes when ready; can retry or replay. |
| **Macro / scripting** | Record sequence of commands; replay later. |
| **Remote control** | Button press sends command to device; same command from app or remote. |
| **Transactional operations** | Each step is a command; rollback = undo each in reverse order. |

---

### 15. Iterator

**Use when:**
- You want to **hide internal structure** (array, tree, graph) and give clients a **uniform way to traverse** (e.g. `next()`, `hasNext()`).
- You support **multiple traversal strategies** (in-order, BFS, filter) without bloating the collection class.
- You want **concurrent iteration** (multiple iterators on the same collection) or **lazy** traversal (one element at a time).

| Example | Why Iterator fits |
|--------|--------------------|
| **Tree with multiple orders** | Pre-order, in-order, BFS iterators; client code only uses `Iterator`. |
| **Filtered view** | Collection exposes `iterator(predicate)` without exposing full list. |
| **Paginated API** | Client uses iterator; implementation fetches next page on `next()`. |
| **Composite traversal** | Iterate over composite (e.g. file system) in DFS/BFS without exposing structure. |
| **Cursor over large result set** | Iterator hides DB cursor and fetches rows on demand. |

---

### 16. Mediator

**Use when:**
- **Many components** could talk to each other directly, but that would create **tight coupling** and **hard-to-follow** flows.
- You want **one central place** that coordinates (e.g. chat room, form validation, wizard steps).
- Adding a **new component or rule** (e.g. new user, new validation) should not require changing every other component.

| Example | Why Mediator fits |
|--------|--------------------|
| **Chat room** | Users send to room; room broadcasts to others; rules (block, rate limit) in one place. |
| **Form / wizard** | Fields don’t talk to each other; mediator validates and enables “Next” when valid. |
| **Aircraft traffic control** | Planes don’t negotiate directly; tower mediates clearances. |
| **Event bus** | Components publish to bus; bus routes to subscribers; no direct references. |
| **Workflow engine** | Steps don’t call each other; engine mediates transition and handoff. |

---

### 17. Memento

**Use when:**
- You need **snapshot and restore** (e.g. rollback, checkpoint) but **don’t want to expose** full internal state.
- The **originator** is the only one that can create and read the memento; others just store and pass it back.
- You want **multiple named snapshots** (e.g. “before-migration”, “v1.0 config”).

| Example | Why Memento fits |
|--------|-------------------|
| **Configuration rollback** | Save snapshot before change; restore on failure. |
| **Undo (state-based)** | Store memento of document state; undo = restore memento (alternative to Command undo). |
| **Checkpoint in game** | Save game state to memento; restore from memento on “load”. |
| **Wizard “back”** | Each step saves a memento; “Back” restores previous step’s memento. |
| **Transactional snapshot** | Before transaction: save memento; on rollback: restore. |

---

### 18. Observer

**Use when:**
- **One subject** (e.g. price feed, form model) has **many dependents** (UI, alerts, analytics) that must **update when the subject changes**.
- You want **loose coupling**: subject doesn’t know concrete observer types; observers subscribe/unsubscribe at runtime.
- You need **push-based** updates (subject notifies observers) rather than polling.

| Example | Why Observer fits |
|--------|---------------------|
| **Stock price alerts** | Price feed notifies all subscribers when price updates; each has own condition. |
| **Model–view** | Model notifies views when data changes; views re-render. |
| **Event-driven UI** | Button click notifies listeners; multiple handlers (save, validate, analytics). |
| **Pub/sub** | Topic notifies subscribers when message arrives. |
| **Feature flags** | Flag service notifies clients when flag value changes. |

---

### 19. State

**Use when:**
- **Behavior depends on internal state** (e.g. idle vs has-money vs dispensing) and you have **many transitions**.
| You want to **avoid big switch/if chains** and keep each state’s behavior in one place.
- Adding a **new state** (e.g. maintenance) should not require editing all other states’ logic.

| Example | Why State fits |
|--------|-----------------|
| **Vending machine** | Idle → HasMoney → Dispensing; each state handles insert, select, cancel differently. |
| **Order lifecycle** | Created → Paid → Shipped → Delivered (or Refunded); each state has different actions. |
| **TCP connection** | Listen → SynReceived → Established → etc.; each state handles segments differently. |
| **Document workflow** | Draft → In Review → Approved → Published; transitions and permissions per state. |
| **Player** | Stopped → Playing → Paused; same “play” button does different things per state. |

---

### 20. Strategy

**Use when:**
- You have **multiple algorithms** for the **same goal** (e.g. route by shortest vs fastest vs avoid tolls) and want to **swap them** at runtime.
- You want to **avoid conditionals** (if format A then algo A, else B) and **add new algorithms** without changing the client.
- The **client** uses one interface; the **concrete strategy** is injected or selected (e.g. by user preference).

| Example | Why Strategy fits |
|--------|--------------------|
| **Route calculator** | ShortestDistance, FastestTime, AvoidTolls; same `getRoute(origin, dest)`. |
| **Sorting** | Compare strategy: by name, by date, by price; same sort routine. |
| **Compression** | Zip, Gzip, LZ4; same compress/decompress API. |
| **Pricing** | Regular, member, bulk discount; same “compute price” API. |
| **Validation** | Strict vs lenient; same “validate” entry point. |

---

### 21. Template Method

**Use when:**
- You have an **algorithm with fixed steps** (e.g. validate → parse → transform → persist) but **step implementation varies** (e.g. by file type).
- You want to **reuse the skeleton** and **avoid duplicating** the flow; subclasses only fill in the variable parts.
- **Order of steps** is fixed; only the “how” of each step changes.

| Example | Why Template Method fits |
|--------|----------------------------|
| **Data import** | validateFile → parse → transform → validateData → persist; CSV/JSON/XML differ in parse/transform. |
| **Test setup** | setup → runTest → tearDown; subclasses override setup/tearDown. |
| **Brew beverage** | boilWater → brew → pour; Tea vs Coffee override brew. |
| **Onboarding** | createAccount → verify → welcome; different flows per region/role. |
| **Report generation** | fetchData → compute → format → deliver; format and deliver vary by report type. |

---

### 22. Visitor

**Use when:**
- You have a **stable structure** (e.g. AST node types) and **many operations** (pretty-print, evaluate, type-check, compile) and you want to **add operations without changing** node classes.
- You’re okay with **adding a new method to the visitor** for each new node type (and a new node type means updating all visitors).
| Operations are **spread over node types**; Visitor keeps each operation in one class.

| Example | Why Visitor fits |
|--------|-------------------|
| **AST** | Literal, BinaryOp, Variable nodes; operations: PrettyPrint, Evaluate, TypeCheck, Compile. |
| **Document model** | Paragraph, Table, Image nodes; operations: Render, Export, WordCount. |
| **UI component tree** | Button, Panel, Label; operations: Layout, Paint, HitTest. |
| **File system** | File, Directory; operations: Size, Find, Duplicate. |
| **Syntax/semantic passes** | One visitor per compiler pass (name resolution, type inference, codegen). |

---

## Quick “Which pattern?” hints

| You need… | Consider |
|-----------|----------|
| Exactly one instance of something | **Singleton** |
| Create one of several product types without caller knowing which | **Factory Method** |
| A consistent family of related products (e.g. theme, platform) | **Abstract Factory** |
| Build something with many optional parts, step by step | **Builder** |
| Cheap copies of something expensive to create | **Prototype** |
| Use an existing API that doesn’t match your interface | **Adapter** |
| Abstraction and implementation to vary independently | **Bridge** |
| Treat leaves and trees the same (uniform ops on hierarchy) | **Composite** |
| Add behavior by wrapping, stackable | **Decorator** |
| One simple API over many subsystems for a use case | **Facade** |
| Many instances sharing most state (save memory) | **Flyweight** |
| Lazy load, access control, or extra behavior around an object | **Proxy** |
| Request handled by one of a chain of handlers | **Chain of Responsibility** |
| Encapsulate action for undo, queue, or replay | **Command** |
| Hide structure, support multiple traversals | **Iterator** |
| Many components; one central coordinator | **Mediator** |
| Snapshot and restore state without exposing it | **Memento** |
| One subject, many dependents that update on change | **Observer** |
| Behavior depends on current state; state-specific logic | **State** |
| Swap algorithm for same goal at runtime | **Strategy** |
| Fixed algorithm skeleton, variable steps (by subtype) | **Template Method** |
| Many operations on a fixed structure; add ops without changing nodes | **Visitor** |

---

*Use this with [LLD Problems by Design Pattern](LLD_PROBLEMS_BY_DESIGN_PATTERN.md) for problem statements and with the code in `src/main/java/lld/designpatterns/` for implementations.*
