package com.example.demo.exception;

/** A booking change was refused. The handler maps the reason to an HTTP status. */
public class BookingException extends RuntimeException {

    public enum Reason {
        NOT_FOUND, BAD_REQUEST, TOO_LATE_TO_CHANGE, SLOT_TAKEN, DAY_FULL, ALREADY_BOOKED
    }

    private final Reason reason;
    private final Long hostId;

    private BookingException(Reason reason, String message, Long hostId) {
        super(message);
        this.reason = reason;
        this.hostId = hostId;
    }

    public Reason getReason() {
        return reason;
    }

    public String getCode() {
        return reason.name();
    }

    /** Host whose open slots should be returned with the error, or null. */
    public Long getHostId() {
        return hostId;
    }

    public static BookingException notFound(String message) {
        return new BookingException(Reason.NOT_FOUND, message, null);
    }

    public static BookingException badRequest(String message) {
        return new BookingException(Reason.BAD_REQUEST, message, null);
    }

    public static BookingException tooLate(String message) {
        return new BookingException(Reason.TOO_LATE_TO_CHANGE, message, null);
    }

    public static BookingException slotTaken(Long hostId) {
        return new BookingException(Reason.SLOT_TAKEN, "This time was just taken", hostId);
    }

    public static BookingException dayFull(Long hostId) {
        return new BookingException(Reason.DAY_FULL, "The host has no more bookings available that day", hostId);
    }

    /** The user already holds an upcoming booking with this host; they should reschedule it instead. */
    public static BookingException alreadyBooked() {
        return new BookingException(Reason.ALREADY_BOOKED,
                "You already have an upcoming booking with this host. Reschedule it instead.", null);
    }
}
