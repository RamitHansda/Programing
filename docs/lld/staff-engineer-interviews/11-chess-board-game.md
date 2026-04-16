# LLD: Chess (or generic two-player board game)

## Interview-ready snapshot

**Say first (≈30s):** **`GameEngine`** runs validate → apply → side switch; **`MoveRule` / Strategy** per piece type; **board** is queried by rules; check detection via **simulation** on copy or dedicated service.

**Default assumptions:** Full rules vs subset—ask immediately; undo optional via **Memento**.

| Phase | ~Time | Deliver |
|-------|------|---------|
| Align | 5 min | Scope of checkmate; undo; Chess960 / variants. |
| Model | 12 min | Game, Board, Piece, Move, rules, check detector. |
| API + flow | 8 min | `applyMove` result type; illegal vs legal. |
| Hard | 12 min | Pinned pieces; king safety; testing with board fixtures. |
| Close | 5 min | AI as separate strategy outside core engine. |

**Whiteboard order:** (1) core types (2) rule interface (3) one piece moves (e.g. rook) (4) check flow (5) engine pipeline.

**Likely probes:** Where does castling live? En passant state? Immutable board?

**30s closer:** Rules are pluggable; engine owns turn and terminal evaluation; representation separate from policy.

---

## Interview prompt

Design **chess**: two players, legal moves, check/checkmate/stalemate detection (scope varies), move history.

## Clarifying questions

- **Full rules** or simplified movement only?
- **Undo** required?
- **AI** player in scope?

## Functional requirements

- Initialize standard board.
- `move(from, to)` validates legality for current player.
- Detect game terminal states (define which you implement).

## Non-functional requirements

- **Extensibility** for new piece types or variants (960 chess).
- **Testability**: movement rules isolated from UI.

## Domain model

| Kind | Type | Responsibility |
|------|------|----------------|
| **Entity** | `Game` / `Match` | Turn side, move history, terminal state; orchestrates apply + validation. |
| **Value object** | `Board` (immutable snapshot) or mutable with copy-on-write | Square occupancy map; queried by rules. |
| **Entity** | `Piece` | Color, type, has-moved flags (castling/pawn double step). |
| **Value object** | `Square`, `Move` | Positions; move is from-to + optional promotion piece. |
| **Strategy / rule** | `MoveRule` / `PieceMovementStrategy` | Legality for a piece kind given board + context (including check). |
| **Domain service** | `CheckDetector` | Simulates moves on a copy to see if king is attacked. |

**Relationships:** `Game` **owns** current `Board` and **mutates** through validated `Move`s only.

**Not modeled:** UI, clock, online pairing server.

## Design patterns

| Pattern | Role |
|--------|------|
| **Strategy** | `PieceMovementStrategy` per piece type (or per piece instance if stateful). |
| **Template Method** | `GameEngine.takeTurn()` skeleton: validate → apply → switch side → evaluate terminal. |
| **Memento** | Snapshot for undo / PGN export. |
| **Null Object** | `EmptySquare` vs `Optional`—either is fine if consistent. |

## Staff-level modeling

- Keep **rules** (`MoveRule`) separate from **board representation** (`Board`).
- **Check detection** as a service that simulates moves on a copy (clear and testable).

## Invariants

- Exactly one king per color (standard chess).
- Turn alternation enforced at engine boundary.
- Move application is atomic; illegal moves do not mutate state.

## Java sketch

```java
public interface MoveRule {
    boolean isLegal(Board board, Move move, GameContext ctx);
}

public final class GameEngine {
    public MoveResult applyMove(Move move) { /* */ }
}
```

## Testing strategy

- Parametric tests per piece from known FEN-like setups (even if you invent a mini notation).
- Regression tests for pinned piece movement.

## Follow-ups

- **Castling/en passant** edge cases, draw rules (threefold repetition), clock/timer.
