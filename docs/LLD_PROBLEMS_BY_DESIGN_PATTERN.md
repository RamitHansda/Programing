# LLD Problems by Design Pattern

One LLD (Low-Level Design) problem per design pattern. Use these for interview prep or to practice applying patterns in real scenarios.

---

## Creational Patterns

### 1. Singleton
**LLD Problem: Database Connection Pool Manager**

Design a **connection pool** that manages a fixed set of database connections. The pool must be the single source of truth for the entire application—no duplicate pools. Support: `getConnection()`, `releaseConnection(conn)`, and configurable pool size. Thread-safe access is required.

*Why it fits:* Exactly one pool instance must exist per process; Singleton ensures that.

---

### 2. Factory Method
**LLD Problem: Document Exporter**

Design a **document export** system: users can export a document as PDF, Word, or Markdown. Each format has different creation steps (headers, encoding, etc.). The system should allow adding new export formats without changing existing export code. Provide an interface like `DocumentExporter.export(doc)` that returns the right concrete exporter based on type.

*Why it fits:* Creation of “product” (exporter) is delegated to subclasses/factory methods; each format is a concrete product.

---

### 3. Abstract Factory
**LLD Problem: UI Kit for Light/Dark Theme**

Design a **UI widget kit** that produces consistent families of widgets: Button, TextBox, Checkbox. There are two themes—Light and Dark—and each theme must supply all widgets that match (e.g. LightButton + LightTextBox, or DarkButton + DarkTextBox). Clients get a factory for a theme and create widgets without knowing concrete classes.

*Why it fits:* You need a family of related products (widgets) that must be used together; Abstract Factory provides theme-specific families.

---

### 4. Builder
**LLD Problem: SQL Query Builder**

Design a **fluent SQL query builder** that constructs SELECT queries step by step: `.select(columns)`, `.from(table)`, `.where(condition)`, `.orderBy(column)`, `.limit(n)`. Optional parts (WHERE, ORDER BY, LIMIT) can be omitted. The same builder should support different query types without telescoping constructors.

*Why it fits:* Complex object (query) with many optional parts; Builder allows step-by-step construction and keeps the API readable.

---

### 5. Prototype
**LLD Problem: Game Entity Cloning**

Design a **game entity system** where entities (e.g. Enemy, Tree, Vehicle) have many attributes (position, health, sprite, AI state). Spawning many similar entities (e.g. 100 zombies from a template) must be cheap. Support cloning an existing entity as a prototype and optionally overriding a few fields (e.g. position) without re-reading from disk or recomputing heavy data.

*Why it fits:* Duplicating complex, pre-configured objects efficiently; Prototype (clone) avoids repeated heavy construction.

---

## Structural Patterns

### 6. Adapter
**LLD Problem: Third-Party Payment Gateway Integration**

Your system expects a single interface: `PaymentProcessor.pay(amount, currency, userId)`. Integrate a **third-party payment SDK** that has a different API: `Gateway.initiatePayment(Request req)` with a different request shape and response format. Design an adapter so the rest of your code only talks to `PaymentProcessor`; the adapter translates to/from the SDK.

*Why it fits:* You need to use an existing component (SDK) whose interface doesn’t match what your code expects; Adapter translates between the two.

---

### 7. Bridge
**LLD Problem: Remote Control for Different Devices**

Design a **remote control** that can control different devices (TV, AC, Speaker). Each device has multiple brands (Sony TV, Samsung TV, etc.) with different low-level APIs (on/off, setChannel, setVolume). The remote’s “buttons” (on, off, next) should be independent of the device implementation so you can add new devices or new remotes without a combinatorial explosion of classes.

*Why it fits:* Decouples abstraction (remote/buttons) from implementation (device-specific APIs); Bridge lets them vary independently.

---

### 8. Composite
**LLD Problem: File System (Directories and Files)**

Model a **file system**: directories can contain files and other directories. Support operations like `getSize()`, `list()`, and `find(name)` that work uniformly on both files and directories (e.g. directory size = sum of children’s sizes). Clients treat “single item” and “group of items” the same way.

*Why it fits:* Tree structure where leaves and composites share the same interface; Composite lets you treat them uniformly.

---

### 9. Decorator
**LLD Problem: Stream Processing Pipeline**

Design a **data stream** abstraction (e.g. `InputStream`-like). Support wrapping a base stream with decorators that add behavior: Compression (read compressed bytes, decompress), Encryption (decrypt on read), Buffering (batch reads), Logging (log every read). Decorators can be stacked in any order. Client code reads from the outermost decorator without knowing the stack.

*Why it fits:* Add responsibilities (compress, encrypt, buffer, log) to an object dynamically without subclassing; Decorator wraps and delegates.

---

### 10. Facade
**LLD Problem: Order Fulfillment Service**

Design a **order fulfillment** API that hides the complexity of: inventory check, payment processing, shipping service, and notification service. Expose a single method like `fulfillOrder(orderId)` that internally coordinates these subsystems. Callers don’t need to know about inventory, payment, or shipping APIs.

*Why it fits:* One simple interface over many subsystems; Facade simplifies usage and encapsulates coordination.

---

### 11. Flyweight
**LLD Problem: Tree Rendering in a Forest**

Design a **forest simulator** that renders thousands of trees. Each tree has shared data (type, texture, mesh) and extrinsic data (position, scale). Memory must stay low when rendering 100k trees. Share one “tree type” object per species and pass position/scale at render time.

*Why it fits:* Many objects with repeated intrinsic state; Flyweight shares that state and keeps only extrinsic state per instance.

---

### 12. Proxy
**LLD Problem: Lazy-Loading Heavy Object**

Design access to a **heavy object** (e.g. large image or report) that is expensive to load from disk or network. Provide a proxy that implements the same interface as the real object. The real object is loaded only when a method is first called (lazy loading). Optionally add access control (e.g. only certain users can call certain methods).

*Why it fits:* Control access and defer expensive construction/loading; Proxy has the same interface as the real subject and forwards when needed.

---

## Behavioral Patterns

### 13. Chain of Responsibility
**LLD Problem: Logging / Request Pipeline**

Design a **logging pipeline**: a log message passes through a chain of handlers. Each handler can either handle the message (e.g. write to file, send to Slack) or pass to the next. Handlers might be: ConsoleLogger, FileLogger, ErrorAlertHandler (only errors), MetricsHandler. Support adding/removing handlers and ordering (e.g. metrics first, then file, then console).

*Why it fits:* Multiple handlers that can process a request; CoR passes the request along until someone handles it.

---

### 14. Command
**LLD Problem: Undo/Redo in a Text Editor**

Design a **text editor** with undo/redo. Every user action (insert text, delete, bold, paste) is encapsulated as a command object with `execute()` and `undo()`. A history stack stores executed commands. Undo pops and calls `undo()`; redo can re-execute. Support macro commands (multiple commands as one).

*Why it fits:* Encapsulate actions as objects so you can queue, log, and reverse them; Command gives execute + undo and enables history.

---

### 15. Iterator
**LLD Problem: Custom Collection with Multiple Traversal Strategies**

Design a **collection** (e.g. in-memory table or tree) that supports multiple ways to traverse: in-order, pre-order, breadth-first, filter-by-predicate. Clients should use a single interface (e.g. `Iterator`) without depending on the internal structure. Support concurrent iteration (multiple iterators on the same collection).

*Why it fits:* Hide internal structure and offer different traversal algorithms; Iterator provides a uniform way to access elements.

---

### 16. Mediator
**LLD Problem: Chat Room**

Design a **chat room** where multiple users send messages. Users don’t message each other directly; they send to the room, and the room broadcasts to others (optionally with rules: e.g. block list, rate limit). Adding a new user or a new rule (e.g. “log all messages”) doesn’t require changing every user class.

*Why it fits:* Many components (users) that could talk to each other; Mediator centralizes communication and keeps components decoupled.

---

### 17. Memento
**LLD Problem: Configuration Snapshot and Restore**

Design a **configuration manager** that holds key-value settings. Support saving a snapshot of the current configuration and later restoring it (e.g. before a risky change, then rollback). The snapshot should not expose internal state to the rest of the app (encapsulation). Optionally support multiple snapshots and naming (e.g. “before-migration”).

*Why it fits:* Capture and restore object state without exposing internals; Memento stores state and restores it on request.

---

### 18. Observer
**LLD Problem: Stock Price Notifications**

Design a **stock price alert system**: users subscribe to symbols (e.g. AAPL, GOOGL) with conditions (e.g. “notify when price > 150”). When the price feed updates, all subscribers whose condition is met get notified (email, push, in-app). Support subscribe/unsubscribe and multiple notification channels per user.

*Why it fits:* One-to-many dependency; when the subject (price feed) changes, observers (subscribers) are notified automatically.

---

### 19. State
**LLD Problem: Vending Machine**

Design a **vending machine** with states: Idle, HasMoney, Dispensing, OutOfStock. Transitions: insert coin → HasMoney; select item (if enough money) → Dispensing; dispense → Idle or OutOfStock. Behavior (e.g. “eject coin”, “dispense item”) depends on current state. Adding a new state (e.g. Maintenance) should not require changing all transition logic.

*Why it fits:* Behavior changes with internal state; State pattern models each state as a class and delegates behavior to the current state.

---

### 20. Strategy
**LLD Problem: Route Calculator (Maps)**

Design a **route calculator** that computes a path between two points. Support multiple strategies: ShortestDistance, FastestTime, AvoidTolls, PublicTransit. The same API (e.g. `getRoute(origin, destination)`) uses the selected strategy. Strategies are interchangeable at runtime; adding a new strategy (e.g. EcoFriendly) doesn’t change the calculator class.

*Why it fits:* Multiple algorithms for the same task; Strategy encapsulates each and makes them swappable.

---

### 21. Template Method
**LLD Problem: Data Import Pipeline**

Design an **import pipeline** for different file types (CSV, JSON, XML). Steps: validate file → parse → transform → validate data → persist. The order of steps is fixed; only the implementation of each step (parse, transform, validate) varies by file type. Support adding new file types by implementing the step variants.

*Why it fits:* Skeleton of an algorithm is fixed; subclasses fill in specific steps; Template Method defines the skeleton and defers steps to subclasses.

---

### 22. Visitor
**LLD Problem: AST (Abstract Syntax Tree) Processing**

Design an **AST** for a simple language (expressions, statements). You need to add new operations on the tree: pretty-print, evaluate, type-check, compile to bytecode. Adding a new operation should not require changing every node class. Design so new operations are added as visitors that “visit” each node type.

*Why it fits:* Many node types and many operations; Visitor lets you add new operations without modifying node classes (open/closed principle).

---

## Quick Reference Table

| Pattern | LLD Problem |
|--------|-------------|
| Singleton | Database connection pool manager |
| Factory Method | Document exporter (PDF/Word/MD) |
| Abstract Factory | UI kit (Light/Dark theme widgets) |
| Builder | SQL query builder |
| Prototype | Game entity cloning |
| Adapter | Third-party payment gateway |
| Bridge | Remote control for multiple devices |
| Composite | File system (files + directories) |
| Decorator | Stream pipeline (compress, encrypt, buffer) |
| Facade | Order fulfillment service |
| Flyweight | Forest/tree rendering (100k trees) |
| Proxy | Lazy-loading heavy object |
| Chain of Responsibility | Logging/request pipeline |
| Command | Text editor undo/redo |
| Iterator | Custom collection, multiple traversals |
| Mediator | Chat room |
| Memento | Configuration snapshot/restore |
| Observer | Stock price alerts |
| State | Vending machine |
| Strategy | Route calculator (maps) |
| Template Method | Data import pipeline (CSV/JSON/XML) |
| Visitor | AST (pretty-print, evaluate, type-check) |

---

*Use these problems to practice: identify the pattern, draw a minimal class diagram, then implement in your language of choice.*
