package lld.orderbook;

/**
 * Order execution type governs how an order interacts with the book.
 *
 * <p>LIMIT   – rests on the book if not immediately matched; default.
 * <p>MARKET  – modelled as a limit at an extreme price (MAX/MIN). At Coinbase, a market order
 *              that would cross the spread always takes liquidity; any unfilled remainder is
 *              cancelled (behaves like IOC in practice).
 * <p>IOC     – Immediate-Or-Cancel: any portion not filled immediately is cancelled.
 * <p>FOK     – Fill-Or-Kill: the full quantity must be fillable right now, otherwise the whole
 *              order is rejected before any match is attempted (no partial fills, no book mutation).
 */
public enum OrderType {
    LIMIT,
    MARKET,
    IOC,
    FOK;

    /** Returns true for types whose unfilled remainder must never rest on the book. */
    public boolean isImmediateOnly() {
        return this == IOC || this == FOK || this == MARKET;
    }
}
