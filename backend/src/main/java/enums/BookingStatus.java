package enums;

/*
 * Represents the different states a booking can be in throughout its lifecycle.
 */
public enum BookingStatus {
    AWAITING_OTP,
    PENDING,
    CONFIRMED,
    CANCELLED,
    EXPIRED
}
