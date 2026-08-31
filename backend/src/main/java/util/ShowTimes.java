package util;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/*
 * Utility class for handling show timings and seat layout generation.
 */
public final class ShowTimes {

    private ShowTimes() {
    }

    // Calculates the end time of a show based on its start time and duration
    public static LocalDateTime endTime(LocalDateTime start, int durationMinutes) {
        return start.plusMinutes(durationMinutes);
    }

    // Checks if a show has already ended
    public static boolean hasEnded(LocalDateTime start, int durationMinutes, LocalDateTime now) {
        return !now.isBefore(endTime(start, durationMinutes));
    }

    // Checks if a show has started
    public static boolean hasStarted(LocalDateTime start, LocalDateTime now) {
        return !now.isBefore(start);
    }

    // Checks if two shows overlap in time
    public static boolean overlaps(
            LocalDateTime startA,
            int durationA,
            LocalDateTime startB,
            int durationB) {

        LocalDateTime endA = endTime(startA, durationA);
        LocalDateTime endB = endTime(startB, durationB);
        return startA.isBefore(endB) && startB.isBefore(endA);
    }

    // Generates a list of row labels from a range string (e.g., "A-D" -> ["A", "B", "C", "D"])
    public static List<String> generateRows(String rowRange) {
        String[] parts = rowRange.split("-");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid row range: " + rowRange);
        }

        char start = Character.toUpperCase(parts[0].trim().charAt(0));
        char end = Character.toUpperCase(parts[1].trim().charAt(0));
        if (start > end) {
            throw new IllegalArgumentException("Invalid row range: " + rowRange);
        }

        List<String> rows = new ArrayList<>();
        for (char current = start; current <= end; current++) {
            rows.add(String.valueOf(current));
        }
        return rows;
    }
}
