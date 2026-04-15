# LLD: Chess (or generic two-player board game)

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
