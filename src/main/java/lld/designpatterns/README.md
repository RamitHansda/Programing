# Design Patterns – LLD Implementations

Staff-level Java implementations for the [LLD Problems by Design Pattern](../../../docs/LLD_PROBLEMS_BY_DESIGN_PATTERN.md) doc. Each package corresponds to one pattern and one problem.

## Layout

| Pattern | Package | Entry / Main types |
|--------|---------|--------------------|
| **Singleton** | `singleton` | `ConnectionPoolManager.initialize(config)`, `getInstance()`, `getConnection()` / `releaseConnection()` |
| **Factory Method** | `factorymethod` | `DocumentExportService.export(doc, format)`; `PdfDocumentExporter`, `WordDocumentExporter`, `MarkdownDocumentExporter` |
| **Abstract Factory** | `abstractfactory` | `UIFactory` (e.g. `LightThemeFactory`, `DarkThemeFactory`); `createButton()`, `createTextBox()`, `createCheckbox()` |
| **Builder** | `builder` | `SelectQuery.builder().select(...).from(...).where(...).orderBy(...).limit(...).build()` |
| **Prototype** | `prototype` | `GameEntity.clone()`; `EntitySpawner(spawner).spawn(n, positions)` |
| **Adapter** | `adapter` | `PaymentGatewayAdapter` implements `PaymentProcessor` and delegates to `ThirdPartyPaymentGateway` |
| **Bridge** | `bridge` | `RemoteControl` + `Device`; `TVRemote(TV)`, `TV` (implementation) |
| **Composite** | `composite` | `FileSystemNode`; `FileNode`, `DirectoryNode` with `getSize()`, `find(name)` |
| **Decorator** | `decorator` | `DataStream`; `BaseStream`, `LoggingDecorator`, `BufferingDecorator`, `CompressionDecorator` |
| **Facade** | `facade` | `OrderFulfillmentService.fulfillOrder(orderId)` – coordinates inventory, payment, shipping, notification |
| **Flyweight** | `flyweight` | `TreeType` (shared), `Tree` (extrinsic x,y,scale); `TreeFactory`, `Forest` |
| **Proxy** | `proxy` | `LazyReportProxy(reportId, loader)` implements `Report`; loads `HeavyReport` on first use |
| **Chain of Responsibility** | `chainofresponsibility` | `LogHandler` chain: `ConsoleLogHandler`, `FileLogHandler`, `ErrorAlertHandler`; `handle(message)` |
| **Command** | `command` | `Command.execute()` / `undo()`; `TextEditor.execute(InsertCommand/DeleteCommand)`, `undo()`, `redo()` |
| **Iterator** | `iterator` | `TreeCollection.iterator(PRE_ORDER|IN_ORDER|BREADTH_FIRST)`; `SimpleTreeCollection` |
| **Mediator** | `mediator` | `ChatRoom.broadcast(sender, text)`; `ChatUser.send(text)` → room → other users |
| **Memento** | `memento` | `ConfigurationManager.saveSnapshot(name)`, `restore(memento)`; `ConfigMemento` |
| **Observer** | `observer` | `StockPriceFeed.subscribe(PriceObserver)`, `setPrice(price)`; observer condition + notification |
| **State** | `state` | `VendingMachineContext`; states `IdleState`, `HasMoneyState`, `DispensingState` |
| **Strategy** | `strategy` | `RouteCalculator(strategy).getRoute(origin, dest)`; `ShortestDistanceStrategy`, `FastestTimeStrategy` |
| **Template Method** | `templatemethod` | `ImportPipeline.run(path)` – validateFile → parse → transform → validateData → persist; `CsvImportPipeline` |
| **Visitor** | `visitor` | `AstNode.accept(visitor)`; `PrettyPrintVisitor`, `EvaluateVisitor`; nodes `LiteralNode`, `BinaryOpNode`, `VariableNode` |

## Conventions

- **Interfaces** for contracts; **final** concrete classes where extension is not intended.
- **Records** for DTOs and config (e.g. `ConnectionPoolConfig`, `PoolStats`, `ExportResult`).
- **Null checks** via `Objects.requireNonNull` on public APIs; validation in constructors/builders.
- **Thread-safety** where specified (e.g. Singleton pool, concurrent collections in mediator/observer).
- **No business logic in constructors** beyond validation and assigning fields; heavy work in factory/startup methods.

## Building / Running

From repo root:

```bash
# Compile
javac -d out src/main/java/lld/designpatterns/**/*.java

# Or use Maven/Gradle if the project uses it
```

Each package is self-contained; you can run or test individual packages (e.g. `singleton`, `command`) without pulling in others.
