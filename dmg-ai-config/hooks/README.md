# Optional hooks (intent)

Wire these in your agent runtime (Cursor hooks, Claude Code hooks, CI) as you adopt the template.

| Hook | Intent |
| --- | --- |
| `pre-commit-secret-scan` | Block obvious secrets before commit |
| `pr-size-warn` | Warn when critical-path packages exceed size budget |
| `require-claude-md` | CI check that `CLAUDE.md` exists in service repos |
| `reschedule-budget-lint` | Custom lint: conflict resolvers reference a budget constant |

Start with secret scan + `CLAUDE.md` presence. Add custom lints only after one real incident justifies them.
