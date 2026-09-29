package interview.rippling.expenseengine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API-shaped return type for {@code evaluateRules}.
 *
 * <p>Managers review both flagged expenses and flagged trips, so both maps are
 * first-class. Keys preserve insertion / evaluation order.</p>
 */
public final class EvaluationResult {
    private final Map<String, List<Violation>> expenseViolations;
    private final Map<String, List<Violation>> tripViolations;

    public EvaluationResult(
            Map<String, List<Violation>> expenseViolations,
            Map<String, List<Violation>> tripViolations) {
        this.expenseViolations = deepCopy(expenseViolations);
        this.tripViolations = deepCopy(tripViolations);
    }

    private static Map<String, List<Violation>> deepCopy(Map<String, List<Violation>> source) {
        Map<String, List<Violation>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<Violation>> entry : source.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    public Map<String, List<Violation>> expenseViolations() {
        return expenseViolations;
    }

    public Map<String, List<Violation>> tripViolations() {
        return tripViolations;
    }

    public boolean hasViolations() {
        return !expenseViolations.isEmpty() || !tripViolations.isEmpty();
    }

    public List<Violation> forExpense(String expenseId) {
        return expenseViolations.getOrDefault(expenseId, List.of());
    }

    public List<Violation> forTrip(String tripId) {
        return tripViolations.getOrDefault(tripId, List.of());
    }

    public List<Violation> allViolations() {
        List<Violation> all = new ArrayList<>();
        expenseViolations.values().forEach(all::addAll);
        tripViolations.values().forEach(all::addAll);
        return List.copyOf(all);
    }

    @Override
    public String toString() {
        return "EvaluationResult{expenses=" + expenseViolations.keySet()
                + ", trips=" + tripViolations.keySet() + "}";
    }
}
