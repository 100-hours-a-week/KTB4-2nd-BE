package com.yeodam.yeodambe.user.security.oauth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginTicketGeneratorTest {

    private final LoginTicketGenerator generator =
            new LoginTicketGenerator();

    @Test
    void generatesUrlSafeRandomTicket() {
        String firstTicket = generator.generate();
        String secondTicket = generator.generate();

        assertThat(firstTicket)
                .hasSize(43)
                .matches("[A-Za-z0-9_-]+");

        assertThat(secondTicket)
                .hasSize(43)
                .matches("[A-Za-z0-9_-]+")
                .isNotEqualTo(firstTicket);
    }
}