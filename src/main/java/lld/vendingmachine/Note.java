package lld.vendingmachine;

/**
 * Enum representing different note denominations
 */
public enum Note {
    ONE(100),
    FIVE(500),
    TEN(1000),
    TWENTY(2000);

    private final int value; // value in cents

    Note(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
