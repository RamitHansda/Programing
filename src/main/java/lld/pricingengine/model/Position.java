package lld.pricingengine.model;

/**
 * An aggregated position: how many units of an instrument a desk holds.
 * A single position corresponds to one pricing request.
 */
public class Position {
    private final String positionId;
    private final Instrument instrument;
    private final double quantity;      // +ve = long, -ve = short
    private final double bookCost;      // historical cost basis

    public Position(String positionId, Instrument instrument, double quantity, double bookCost) {
        this.positionId  = positionId;
        this.instrument  = instrument;
        this.quantity    = quantity;
        this.bookCost    = bookCost;
    }

    public String getPositionId()      { return positionId; }
    public Instrument getInstrument()  { return instrument; }
    public double getQuantity()        { return quantity; }
    public double getBookCost()        { return bookCost; }

    @Override
    public String toString() {
        return String.format("Position{id=%s, qty=%.0f, instrument=%s}", positionId, quantity, instrument);
    }
}
