package lld.sealed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link Result} sealed type and {@link OrderValidationService}.
 *
 * Each nested class maps to one behaviour: factory methods, map, flatMap,
 * validation rules, and the exhaustive-switch contract.
 */
class ResultTest {

    private final OrderValidationService svc = new OrderValidationService();

    // =========================================================================
    // Factory methods
    // =========================================================================

    @Nested
    @DisplayName("Result.ok / Result.fail — factory methods")
    class Factory {

        @Test
        @DisplayName("ok() produces a Success carrying the value")
        void okProducesSuccess() {
            Result<String> r = Result.ok("hello");

            assertInstanceOf(Result.Success.class, r);
            assertEquals("hello", ((Result.Success<String>) r).value());
        }

        @Test
        @DisplayName("fail() produces a Failure with the given code and message")
        void failProducesFailure() {
            Result<String> r = Result.fail("ERR_CODE", "something went wrong");

            assertInstanceOf(Result.Failure.class, r);
            var f = (Result.Failure<String>) r;
            assertEquals("ERR_CODE",             f.code());
            assertEquals("something went wrong", f.message());
        }

        @Test
        @DisplayName("ok(null) throws — null value is rejected")
        void okNullThrows() {
            assertThrows(IllegalArgumentException.class, () -> Result.ok(null));
        }

        @Test
        @DisplayName("fail with blank code throws")
        void failBlankCodeThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> Result.fail("  ", "msg"));
        }
    }

    // =========================================================================
    // isSuccess / isFailure helpers
    // =========================================================================

    @Nested
    @DisplayName("isSuccess / isFailure predicates")
    class Predicates {

        @Test void successIsSuccess() { assertTrue(Result.ok(1).isSuccess()); }
        @Test void successIsNotFailure() { assertFalse(Result.ok(1).isFailure()); }
        @Test void failureIsFailure() { assertTrue(Result.fail("E", "m").isFailure()); }
        @Test void failureIsNotSuccess() { assertFalse(Result.fail("E", "m").isSuccess()); }
    }

    // =========================================================================
    // map()
    // =========================================================================

    @Nested
    @DisplayName("map() — transforms success value, propagates failure")
    class MapTests {

        @Test
        @DisplayName("map on Success applies the function")
        void mapSuccess() {
            Result<Integer> r = Result.<String>ok("hello").map(String::length);

            assertInstanceOf(Result.Success.class, r);
            assertEquals(5, ((Result.Success<Integer>) r).value());
        }

        @Test
        @DisplayName("map on Failure returns the same Failure, function not called")
        void mapFailurePropagates() {
            boolean[] called = { false };
            Result<Integer> r = Result.<String>fail("ERR", "bad")
                    .map(s -> { called[0] = true; return s.length(); });

            assertInstanceOf(Result.Failure.class, r);
            assertFalse(called[0], "mapper must not be called on a Failure");
            assertEquals("ERR", ((Result.Failure<Integer>) r).code());
        }
    }

    // =========================================================================
    // flatMap()
    // =========================================================================

    @Nested
    @DisplayName("flatMap() — chains operations, short-circuits on first failure")
    class FlatMapTests {

        @Test
        @DisplayName("flatMap on Success chains to the next step")
        void flatMapSuccess() {
            Result<Integer> r = Result.ok("42")
                    .flatMap(s -> Result.ok(Integer.parseInt(s)));

            assertEquals(42, ((Result.Success<Integer>) r).value());
        }

        @Test
        @DisplayName("flatMap stops at first Failure and does not call subsequent mappers")
        void flatMapShortCircuits() {
            var secondCalled = new boolean[]{ false };

            Result<String> r = Result.<String>fail("STEP1_FAIL", "first step failed")
                    .flatMap(s -> { secondCalled[0] = true; return Result.ok(s.toUpperCase()); });

            assertFalse(secondCalled[0], "second mapper must not run after a Failure");
            assertEquals("STEP1_FAIL", ((Result.Failure<String>) r).code());
        }

        @Test
        @DisplayName("chain of three steps — failure in the middle stops the chain")
        void chainMiddleFailure() {
            Result<String> r = Result.ok("start")
                    .flatMap(s -> Result.fail("MID_FAIL", "second step failed"))
                    .flatMap(s -> Result.ok("should not reach here"));

            assertInstanceOf(Result.Failure.class, r);
            assertEquals("MID_FAIL", ((Result.Failure<String>) r).code());
        }
    }

    // =========================================================================
    // OrderValidationService — validation rules
    // =========================================================================

    @Nested
    @DisplayName("OrderValidationService — validation rules")
    class ValidationRules {

        @Test
        @DisplayName("All valid inputs produce a Success with a ValidatedOrder")
        void allValidProducesSuccess() {
            var r = svc.validate("O-1", "BTC-USD",
                    new BigDecimal("50000"), new BigDecimal("1"));

            assertInstanceOf(Result.Success.class, r);
            var order = ((Result.Success<OrderValidationService.ValidatedOrder>) r).value();
            assertEquals("O-1",    order.orderId());
            assertEquals("BTC-USD", order.symbol());
        }

        @Test
        @DisplayName("Blank orderId → MISSING_ORDER_ID failure")
        void blankOrderId() {
            var r = svc.validate("", "BTC-USD", new BigDecimal("100"), new BigDecimal("1"));
            assertFailure(r, "MISSING_ORDER_ID");
        }

        @Test
        @DisplayName("Blank symbol → MISSING_SYMBOL failure")
        void blankSymbol() {
            var r = svc.validate("O-1", "", new BigDecimal("100"), new BigDecimal("1"));
            assertFailure(r, "MISSING_SYMBOL");
        }

        @Test
        @DisplayName("Negative price → INVALID_PRICE failure")
        void negativePrice() {
            var r = svc.validate("O-1", "BTC-USD", new BigDecimal("-1"), new BigDecimal("1"));
            assertFailure(r, "INVALID_PRICE");
        }

        @Test
        @DisplayName("Zero quantity → INVALID_QUANTITY failure")
        void zeroQuantity() {
            var r = svc.validate("O-1", "BTC-USD", new BigDecimal("100"), BigDecimal.ZERO);
            assertFailure(r, "INVALID_QUANTITY");
        }

        @Test
        @DisplayName("Only the FIRST failure is reported — subsequent rules are skipped")
        void firstFailureShortCircuits() {
            // Both orderId and price are invalid; only orderId failure should surface
            var r = svc.validate("", "BTC-USD", new BigDecimal("-1"), new BigDecimal("1"));
            assertFailure(r, "MISSING_ORDER_ID");
        }
    }

    // =========================================================================
    // Gateway response — exhaustive switch
    // =========================================================================

    @Nested
    @DisplayName("toGatewayResponse — exhaustive switch on sealed Result")
    class GatewayResponse {

        @Test
        @DisplayName("Success produces a 200 response")
        void successResponse() {
            var r = svc.validate("O-1", "BTC-USD",
                    new BigDecimal("50000"), new BigDecimal("1"));
            assertTrue(svc.toGatewayResponse(r).startsWith("200 OK"));
        }

        @Test
        @DisplayName("Failure produces a 400 response containing the error code")
        void failureResponse() {
            var r = svc.validate("", "BTC-USD",
                    new BigDecimal("50000"), new BigDecimal("1"));
            String resp = svc.toGatewayResponse(r);
            assertTrue(resp.startsWith("400 Bad Request"));
            assertTrue(resp.contains("MISSING_ORDER_ID"));
        }
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private static void assertFailure(Result<?> r, String expectedCode) {
        assertInstanceOf(Result.Failure.class, r,
                "expected Failure with code=" + expectedCode + " but got " + r.getClass().getSimpleName());
        assertEquals(expectedCode, ((Result.Failure<?>) r).code());
    }
}
