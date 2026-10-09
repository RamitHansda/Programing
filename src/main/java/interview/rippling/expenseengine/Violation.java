package interview.rippling.expenseengine;

import java.util.Objects;

/**
 * One policy hit. An expense or trip may accumulate multiple violations —
 * e.g. sample expense {@code 004} is both banned airfare and over $250.
 */
public final class Violation {
    private final String ruleId;
    private final String ruleName;
    private final RuleScope scope;
    private final String subjectId;
    private final String message;

    public Violation(String ruleId, String ruleName, RuleScope scope, String subjectId, String message) {
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
        this.ruleName = Objects.requireNonNull(ruleName, "ruleName");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.subjectId = Objects.requireNonNull(subjectId, "subjectId");
        this.message = Objects.requireNonNull(message, "message");
    }

    public String ruleId() {
        return ruleId;
    }

    public String ruleName() {
        return ruleName;
    }

    public RuleScope scope() {
        return scope;
    }

    public String subjectId() {
        return subjectId;
    }

    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return scope + "[" + subjectId + "] " + ruleId + ": " + message;
    }
}
