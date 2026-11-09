package com.yeodam.yeodambe.common.validation;

public final class TextValidation {
    private TextValidation() {
    }

    public static boolean hasVisibleContent(String value) {
        return value != null && value.codePoints().anyMatch(codePoint -> !isInvisible(codePoint));
    }

    public static boolean containsInvisible(String value) {
        return value != null && value.codePoints().anyMatch(TextValidation::isInvisible);
    }

    public static boolean containsUnpairedSurrogate(String value) {
        return value != null && value.codePoints()
                .anyMatch(codePoint -> Character.getType(codePoint) == Character.SURROGATE);
    }

    public static String stripInvisible(String value) {
        if (value == null) {
            return null;
        }
        int start = 0;
        int end = value.length();
        while (start < end && isInvisible(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && isInvisible(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return value.substring(start, end);
    }

    private static boolean isInvisible(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || Character.getType(codePoint) == Character.FORMAT
                || codePoint == 0x3164
                || codePoint == 0x2800;
    }
}
