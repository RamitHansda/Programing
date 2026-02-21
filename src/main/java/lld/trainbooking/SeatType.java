package lld.trainbooking;

/**
 * Enum representing different types of seats/classes in a train
 */
public enum SeatType {
    SLEEPER("SL", 500.0),
    AC_3_TIER("3A", 1000.0),
    AC_2_TIER("2A", 1500.0),
    AC_1_TIER("1A", 2500.0),
    SECOND_SITTING("2S", 300.0),
    AC_CHAIR_CAR("CC", 800.0);

    private final String code;
    private final double basePrice;

    SeatType(String code, double basePrice) {
        this.code = code;
        this.basePrice = basePrice;
    }

    public String getCode() {
        return code;
    }

    public double getBasePrice() {
        return basePrice;
    }
}
