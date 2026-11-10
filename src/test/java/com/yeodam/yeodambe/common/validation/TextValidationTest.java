package com.yeodam.yeodambe.common.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class TextValidationTest {

    @ParameterizedTest
    @ValueSource(strings = {
            " ", "\t", "\u00A0", "\u2007", "\u202F", "\u200B", "\u2060", "\uFEFF", "\u3164", "\u2800"
    })
    void 앞뒤의_보이지_않는_문자를_제거한다(String invisible) {
        String value = invisible + "제주" + invisible;

        assertThat(TextValidation.stripInvisible(value)).isEqualTo("제주");
    }

    @Test
    void 여러_종류가_섞인_앞뒤_특수_공백을_모두_제거한다() {
        String value = " \u00A0\u200B\uFEFF제주 여행\u3164\u2800\u2060 ";

        assertThat(TextValidation.stripInvisible(value)).isEqualTo("제주 여행");
    }

    @ParameterizedTest
    @ValueSource(strings = {"제주 여행", "제주\u200B여행", "👨‍👩‍👧‍👦", "e\u0301", "😀가😀"})
    void 내부_문자와_이모지와_결합_문자는_보존한다(String value) {
        assertThat(TextValidation.stripInvisible(value)).isEqualTo(value);
    }

    @Test
    void 보이지_않는_문자만_있으면_빈_문자열을_반환한다() {
        assertThat(TextValidation.stripInvisible(" \u00A0\u200B\uFEFF\u3164 ")).isEmpty();
    }

    @Test
    void null은_null로_반환한다() {
        assertThat(TextValidation.stripInvisible(null)).isNull();
    }

    @Test
    void 보이지_않는_문자_포함_여부를_판정한다() {
        assertThat(TextValidation.containsInvisible("user\u200B@test.com")).isTrue();
        assertThat(TextValidation.containsInvisible("user @test.com")).isTrue();
        assertThat(TextValidation.containsInvisible("user@test.com")).isFalse();
        assertThat(TextValidation.containsInvisible(null)).isFalse();
    }

    @Test
    void 보이는_글자_포함_여부_판정은_기존_기준을_유지한다() {
        assertThat(TextValidation.hasVisibleContent(" \u00A0\u200B\uFEFF\u3164\u2800 ")).isFalse();
        assertThat(TextValidation.hasVisibleContent(null)).isFalse();
        assertThat(TextValidation.hasVisibleContent("\u200B가")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\uD800", "\uDC00", "\uDC00\uD800", "가\uD800", "😀\uDC00"})
    void 짝이_깨진_서로게이트를_찾는다(String value) {
        assertThat(TextValidation.containsUnpairedSurrogate(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "제주", "😀", "👨‍👩‍👧‍👦", "e\u0301", "\uDBFF\uDFFF"})
    void 정상_서로게이트_쌍과_일반_문자는_허용한다(String value) {
        assertThat(TextValidation.containsUnpairedSurrogate(value)).isFalse();
    }

    @Test
    void null은_잘못된_서로게이트를_포함하지_않는다() {
        assertThat(TextValidation.containsUnpairedSurrogate(null)).isFalse();
    }
}
