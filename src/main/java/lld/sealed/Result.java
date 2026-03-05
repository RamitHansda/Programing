package lld.sealed;

import java.util.function.Function;

/**
 * <h2>Result&lt;T&gt; — a sealed success-or-failure type</h2>
 *
 * <p>Models the outcome of an operation that can either succeed with a value
 * or fail with a structured error.  This is the most common application of
 * sealed interfaces: replacing unchecked exceptions or nullable returns with a
 * type that forces the caller to handle both cases explicitly.
 *
 * <pre>
 *  Result&lt;T&gt;  (sealed)
 *    ├── Success&lt;T&gt;   — operation succeeded; carries the result value
 *    └── Failure       — operation failed;   carries an error code + message
 * </pre>
 *
 * <h3>Why sealed here?</h3>
 * <ul>
 *   <li>The compiler guarantees every {@code switch} covers both arms — no
 *       silent "I forgot the error case" bugs.</li>
 *   <li>Adding a third variant (e.g. {@code Pending}) causes a compile error
 *       at every unhandled switch site — change propagates safely.</li>
 *   <li>Records give free {@code equals}/{@code hashCode} for test assertions.</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * Result<Order> result = validationService.validate(cmd);
 *
 * String response = switch (result) {
 *     case Result.Success<Order> s -> "Accepted: "  + s.value().orderId();
 *     case Result.Failure        f -> "Rejected("   + f.code() + "): " + f.message();
 * };
 * }</pre>
 *
 * @param <T> the type of the success value
 */
public sealed interface Result<T> permits Result.Success, Result.Failure {

    // -------------------------------------------------------------------------
    // Permitted implementations
    // -------------------------------------------------------------------------

    /**
     * The operation completed successfully.
     *
     * @param value the result value (non-null by convention — use {@code Result<Optional<T>>}
     *              if absence is meaningful in the success case)
     */
    record Success<T>(T value) implements Result<T> {

        public Success {
            if (value == null) throw new IllegalArgumentException("Success value must not be null");
        }
    }

    /**
     * The operation failed with a structured error.
     *
     * @param code    machine-readable error code (e.g. "INVALID_PRICE", "ORDER_NOT_FOUND")
     * @param message human-readable explanation (for logs / client responses)
     */
    record Failure<T>(String code, String message) implements Result<T> {

        public Failure {
            if (code    == null || code.isBlank())    throw new IllegalArgumentException("code must not be blank");
            if (message == null || message.isBlank()) throw new IllegalArgumentException("message must not be blank");
        }
    }

    // -------------------------------------------------------------------------
    // Convenience factory methods
    // -------------------------------------------------------------------------

    /** Creates a {@link Success} result wrapping {@code value}. */
    static <T> Result<T> ok(T value) {
        return new Success<>(value);
    }

    /** Creates a {@link Failure} result with the given {@code code} and {@code message}. */
    static <T> Result<T> fail(String code, String message) {
        return new Failure<>(code, message);
    }

    // -------------------------------------------------------------------------
    // Functional combinators
    // -------------------------------------------------------------------------

    /**
     * Transforms the success value without unwrapping.
     * If this is already a {@link Failure}, the failure propagates unchanged.
     *
     * <pre>{@code
     * Result<String>  nameResult  = Result.ok("Alice");
     * Result<Integer> lengthResult = nameResult.map(String::length); // Success(5)
     *
     * Result<String>  error  = Result.fail("NOT_FOUND", "user missing");
     * Result<Integer> still  = error.map(String::length);            // Failure unchanged
     * }</pre>
     */
    default <U> Result<U> map(Function<T, U> mapper) {
        return switch (this) {
            case Success<T> s -> Result.ok(mapper.apply(s.value()));
            case Failure<T> f -> Result.fail(f.code(), f.message());
        };
    }

    /**
     * Chains a second operation that itself returns a {@link Result}.
     * Stops and returns the first failure encountered.
     *
     * <pre>{@code
     * Result<Order> result = parseOrderId(input)
     *         .flatMap(id -> fetchOrder(id))
     *         .flatMap(order -> validateRisk(order));
     * }</pre>
     */
    default <U> Result<U> flatMap(Function<T, Result<U>> mapper) {
        return switch (this) {
            case Success<T> s -> mapper.apply(s.value());
            case Failure<T> f -> Result.fail(f.code(), f.message());
        };
    }

    /** Returns {@code true} if this is a {@link Success}. */
    default boolean isSuccess() {
        return this instanceof Success;
    }

    /** Returns {@code true} if this is a {@link Failure}. */
    default boolean isFailure() {
        return this instanceof Failure;
    }
}
