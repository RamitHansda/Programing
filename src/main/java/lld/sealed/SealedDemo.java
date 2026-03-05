package lld.sealed;

import java.math.BigDecimal;

/**
 * Runnable demo — prints the output of every case so you can trace the
 * sealed interface behaviour end-to-end.
 *
 * <p>Run with: {@code mvn exec:java -Dexec.mainClass=lld.sealed.SealedDemo}
 */
public class SealedDemo {

    public static void main(String[] args) {
        var svc = new OrderValidationService();

        System.out.println("=== Sealed Interface — Result<T> Demo ===\n");

        // ── Case 1: valid order ───────────────────────────────────────────────
        System.out.println("── Case 1: all fields valid ──");
        var r1 = svc.validate("ORD-001", "BTC-USD",
                new BigDecimal("50000"), new BigDecimal("0.5"));
        System.out.println("  result type : " + r1.getClass().getSimpleName());
        System.out.println("  isSuccess   : " + r1.isSuccess());
        System.out.println("  response    : " + svc.toGatewayResponse(r1));
        System.out.println();

        // ── Case 2: blank orderId — first rule fails, rest skipped ────────────
        System.out.println("── Case 2: blank orderId ──");
        var r2 = svc.validate("", "BTC-USD",
                new BigDecimal("50000"), new BigDecimal("0.5"));
        System.out.println("  result type : " + r2.getClass().getSimpleName());
        System.out.println("  isFailure   : " + r2.isFailure());
        System.out.println("  response    : " + svc.toGatewayResponse(r2));
        System.out.println();

        // ── Case 3: negative price ────────────────────────────────────────────
        System.out.println("── Case 3: negative price ──");
        var r3 = svc.validate("ORD-003", "ETH-USD",
                new BigDecimal("-1"), new BigDecimal("2"));
        System.out.println("  result type : " + r3.getClass().getSimpleName());
        System.out.println("  response    : " + svc.toGatewayResponse(r3));
        System.out.println();

        // ── Case 4: zero quantity ─────────────────────────────────────────────
        System.out.println("── Case 4: zero quantity ──");
        var r4 = svc.validate("ORD-004", "SOL-USD",
                new BigDecimal("100"), BigDecimal.ZERO);
        System.out.println("  result type : " + r4.getClass().getSimpleName());
        System.out.println("  response    : " + svc.toGatewayResponse(r4));
        System.out.println();

        // ── Case 5: map() on success ──────────────────────────────────────────
        System.out.println("── Case 5: map() transforms a Success ──");
        Result<String> nameResult   = Result.ok("Alice");
        Result<Integer> lengthResult = nameResult.map(String::length);
        System.out.println("  original : " + nameResult);
        System.out.println("  mapped   : " + lengthResult);
        System.out.println();

        // ── Case 6: map() on a Failure propagates the error unchanged ─────────
        System.out.println("── Case 6: map() on a Failure — error propagates ──");
        Result<String>  error  = Result.fail("NOT_FOUND", "user does not exist");
        Result<Integer> mapped = error.map(String::length);
        System.out.println("  original : " + error);
        System.out.println("  mapped   : " + mapped);
        System.out.println();

        // ── Case 7: exhaustive switch — read the code, see the guarantee ──────
        System.out.println("── Case 7: exhaustive switch (no default needed) ──");
        Object[] results = { r1, r2, r3, r4 };
        for (Object r : results) {
            // Compiler enforces that this switch handles ALL permitted types.
            // Remove either case → compile error.
            String label = switch ((Result<?>) r) {
                case Result.Success<?> s -> "SUCCESS value=" + s.value();
                case Result.Failure<?> f -> "FAILURE code=" + f.code();
            };
            System.out.println("  " + label);
        }
    }
}
