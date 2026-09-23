# LLD Design Patterns — Day-of Cheatsheet

Print / keep open. Full guide: [LLD_DESIGN_PATTERNS_INTERVIEW_GUIDE.md](./LLD_DESIGN_PATTERNS_INTERVIEW_GUIDE.md).

## Opening line

> Domain + invariants first. Patterns only where algorithms, lifecycle, or fan-out vary.

## Decision (10 seconds)

| Variation | Pattern | Say |
|-----------|---------|-----|
| Same goal, different algorithm | **Strategy** | “Inject policy; no god switch” |
| Behavior depends on status | **State** | “Illegal transitions impossible by construction” |
| 1 → many react to change | **Observer** | “Subscribe without hardcoding listeners” |
| Create by type | **Factory** | “Callers depend on interface” |
| Stack cross-cutting | **Decorator** | “Wrap interface; core unchanged” |
| Tree leaf + container | **Composite** | “Uniform ops; recurse” |
| Ordered handlers | **Chain** | “Handle or forward; reorderable” |
| Undo / queue / replay | **Command** | “Action as object + history” |
| Many optional fields | **Builder** | “Fluent build; validate at end” |
| Foreign API mismatch | **Adapter** | “Our port, their SDK” |

## Problem → pattern (memorize)

| Prompt | Primary | Also |
|--------|---------|------|
| Parking lot | Strategy + Factory | — |
| Elevator | State + Strategy | — |
| Vending | State | Strategy change |
| Logger | Chain | Decorator |
| LRU / cache | Strategy | Decorator |
| Rate limiter | Strategy | — |
| Splitwise | Strategy | Money VO |
| Chess | Strategy | Template / Memento |
| Filesystem | Composite | Iterator |
| Notifications | Strategy | Observer |
| Pub-sub | Observer | Mediator / Strategy |
| Booking (seats/events) | State | Hold→confirm |
| Checkout | Strategy | Builder / Saga |
| Editor | Command | — |
| Pool | Singleton* | Prefer DI |

\*Prefer “one injected instance” over classic Singleton.

## Whiteboard plug-in sketch

```text
Service
  └─ XxxStrategy / State / Channel   ← name this seam
       ├─ ImplA
       └─ ImplB
```

## Don’t say

- Patterns before nouns/invariants
- Singleton “because there’s one”
- Three patterns when one seam is enough

## Staff close (30s)

> “Variation plugs in at **X**. Consistency boundary is **Y**. Under contention I’ll **[lock / per-key serialize / approximate]**.”

## Deep links

- Playbook: [staff-engineer-interviews/README.md](./staff-engineer-interviews/README.md)
- When-to-use: [../DESIGN_PATTERNS_WHEN_TO_USE_AND_EXAMPLES.md](../DESIGN_PATTERNS_WHEN_TO_USE_AND_EXAMPLES.md)
- Practice problems: [../LLD_PROBLEMS_BY_DESIGN_PATTERN.md](../LLD_PROBLEMS_BY_DESIGN_PATTERN.md)
- Java: `src/main/java/lld/designpatterns/`
