package interview.rippling.expenseengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RuleEngineTest {

    private RuleEngine engine;
    private List<ExpensePolicyRule> rules;

    @BeforeEach
    void setUp() {
        engine = new RuleEngine();
        rules = RuleEngine.defaultTravelPolicies();
    }

    @Test
    void sampleData_flagsExpectedExpensesAndPreservesMultipleViolationsOn004() {
        EvaluationResult result = engine.evaluateRules(rules, SampleData.expenses());

        assertEquals(Set.of("003", "004", "007"), result.expenseViolations().keySet());
        assertEquals(2, result.forExpense("004").size(), "004 must keep both airfare and max-$250 hits");

        Set<String> rulesOn004 = result.forExpense("004").stream()
                .map(Violation::ruleId)
                .collect(Collectors.toSet());
        assertEquals(Set.of("R2", "R4"), rulesOn004);

        assertEquals(1, result.forExpense("003").size());
        assertEquals("R1", result.forExpense("003").get(0).ruleId());

        assertEquals(1, result.forExpense("007").size());
        assertEquals("R3", result.forExpense("007").get(0).ruleId());
    }

    @Test
    void sampleData_flagsTrip002ForTotalAndMealsAggregates() {
        EvaluationResult result = engine.evaluateRules(rules, SampleData.expenses());

        assertEquals(Set.of("002"), result.tripViolations().keySet());
        assertEquals(2, result.forTrip("002").size());

        Set<String> tripRules = result.forTrip("002").stream()
                .map(Violation::ruleId)
                .collect(Collectors.toSet());
        assertEquals(Set.of("R5", "R6"), tripRules);

        assertTrue(result.forTrip("001").isEmpty());
        assertTrue(result.forTrip("003").isEmpty());
    }

    @Test
    void amountsAreComparedNumericallyNotLexicographically() {
        // Lexicographic string compare: "999" > "1000" is true — that bug must not exist.
        List<ExpensePolicyRule> max250 = List.of(MaxAmountRule.anyExpense("MAX", new BigDecimal("250")));
        List<Map<String, String>> expenses = List.of(
                SampleData.expense("E1", "T1", "999", "supplies", "retailer", "Store"),
                SampleData.expense("E2", "T1", "1000", "supplies", "retailer", "Store"),
                SampleData.expense("E3", "T1", "49.99", "supplies", "retailer", "Store"));

        EvaluationResult result = engine.evaluateRules(max250, expenses);

        assertEquals(Set.of("E1", "E2"), result.expenseViolations().keySet());
        assertTrue(result.forExpense("E3").isEmpty());
    }

    @Test
    void boundary_exactlyAtThresholdIsAllowed() {
        List<ExpensePolicyRule> restaurant75 =
                List.of(MaxAmountRule.forVendorType("R1", "restaurant", new BigDecimal("75")));
        List<Map<String, String>> expenses = List.of(
                SampleData.expense("E1", "T1", "75.00", "meals", "restaurant", "Cafe"),
                SampleData.expense("E2", "T1", "75.01", "meals", "restaurant", "Cafe"));

        EvaluationResult result = engine.evaluateRules(restaurant75, expenses);

        assertTrue(result.forExpense("E1").isEmpty());
        assertEquals(1, result.forExpense("E2").size());
    }

    @Test
    void restaurantRuleUsesVendorTypeNotExpenseType() {
        // Expense 001 is supplies at a restaurant — under $75 so it must NOT flag.
        // A $80 supplies purchase at a restaurant MUST flag (vendor_type rule).
        List<ExpensePolicyRule> restaurant75 =
                List.of(MaxAmountRule.forVendorType("R1", "restaurant", new BigDecimal("75")));
        List<Map<String, String>> expenses = List.of(
                SampleData.expense("001", "001", "49.99", "supplies", "restaurant", "Outback"),
                SampleData.expense("X80", "001", "80.00", "supplies", "restaurant", "Outback"),
                SampleData.expense("M80", "001", "80.00", "meals", "retailer", "Grocery"));

        EvaluationResult result = engine.evaluateRules(restaurant75, expenses);

        assertTrue(result.forExpense("001").isEmpty());
        assertEquals(1, result.forExpense("X80").size());
        assertTrue(result.forExpense("M80").isEmpty(), "meals at a retailer is not a restaurant vendor");
    }

    @Test
    void malformedAmountThrows() {
        List<Map<String, String>> bad = List.of(
                SampleData.expense("E1", "T1", "not-a-number", "meals", "restaurant", "Cafe"));
        assertThrows(IllegalArgumentException.class, () -> engine.evaluateRules(rules, bad));
    }

    @Test
    void emptyRulesMeansNoViolations() {
        EvaluationResult result = engine.evaluateRules(List.of(), SampleData.expenses());
        assertFalse(result.hasViolations());
    }

    @Test
    void managerCanPassSubsetOfRules() {
        // Only ban entertainment — airfare 004 should pass.
        List<ExpensePolicyRule> subset = List.of(BanFieldRule.banExpenseType("R3", "entertainment"));
        EvaluationResult result = engine.evaluateRules(subset, SampleData.expenses());

        assertEquals(Set.of("007"), result.expenseViolations().keySet());
        assertTrue(result.tripViolations().isEmpty());
    }
}
