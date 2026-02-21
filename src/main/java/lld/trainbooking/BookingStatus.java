package lld.trainbooking;

/**
 * Enum representing different states of a booking
 */
public enum BookingStatus {
    CONFIRMED,
    WAITLISTED,
    RAC,  // Reservation Against Cancellation
    CANCELLED,
    FAILED
}
