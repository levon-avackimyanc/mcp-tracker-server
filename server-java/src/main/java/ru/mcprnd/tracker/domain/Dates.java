package ru.mcprnd.tracker.domain;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Работа с датами в формате ISO (yyyy-MM-dd). */
public final class Dates {

    private Dates() {
    }

    public static boolean isIsoDate(String value) {
        if (value == null || value.length() != 10) {
            return false;
        }
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    public static String isoDaysFromNow(int days) {
        return LocalDate.now().plusDays(days).toString();
    }

    public static String today() {
        return LocalDate.now().toString();
    }
}
