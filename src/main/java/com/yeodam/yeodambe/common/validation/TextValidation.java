package com.yeodam.yeodambe.common.validation;

public final class TextValidation {
    private TextValidation() {
    }

    public static boolean hasVisibleContent(String value) {
        return value != null && value.codePoints().anyMatch(codePoint ->
                !Character.isWhitespace(codePoint)
                        && !Character.isSpaceChar(codePoint)
                        && Character.getType(codePoint) != Character.FORMAT
                        && codePoint != 0x3164
                        && codePoint != 0x2800);
    }
}
