package com.yeodam.websocket.trip.service;

import com.yeodam.websocket.trip.client.response.TripPresenceAccessResponse.AccessData;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class TripPresenceSessionRegistryTest {
    @Test
    void 종료된_연결은_늦은_권한_응답으로_복구되지_않는다() {
        TripPresenceSessionRegistry registry = new TripPresenceSessionRegistry();
        TripPresenceSessionRegistry.Connection connection = registry.connection("socket");
        registry.subscribe(connection, "sub", 10L, 유효한_권한_생성());
        registry.close("socket");
        assertThatThrownBy(() -> registry.updateAccess(connection, 10L, 유효한_권한_생성()))
                .hasMessage("인증이 필요합니다.");
        assertThat(registry.canDeliver("socket", "sub", 10L, "epoch", Instant.now())).isFalse();
    }

    @Test
    void 입장하지_않은_구독자도_유효한_권한과_일치하는_epoch가_필요하다() {
        TripPresenceSessionRegistry registry = new TripPresenceSessionRegistry();
        TripPresenceSessionRegistry.Connection connection = registry.connection("socket");
        AccessData access = 유효한_권한_생성();
        registry.subscribe(connection, "sub", 10L, access);
        registry.acceptEpoch(10L, "epoch");
        assertThat(registry.canDeliver("socket", "sub", 10L, "epoch", Instant.now())).isTrue();
        assertThat(registry.canDeliver("socket", "sub", 10L, "old", Instant.now())).isFalse();
        assertThat(registry.canDeliver("socket", "sub", 10L, "epoch", access.accessValidUntil())).isFalse();
    }

    @Test
    void 구독_ID는_다른_여행에_재사용할_수_없다() {
        TripPresenceSessionRegistry registry = new TripPresenceSessionRegistry();
        TripPresenceSessionRegistry.Connection connection = registry.connection("socket");
        registry.subscribe(connection, "sub", 10L, 유효한_권한_생성());
        assertThatThrownBy(() -> registry.subscribe(connection, "sub", 11L, 유효한_권한_생성()))
                .hasMessage("요청이 올바르지 않습니다.");
    }

    private AccessData 유효한_권한_생성() {
        Instant now = Instant.now();
        return new AccessData(10L, 1L, "지형", null, "READ", now, now.plusSeconds(30),
                now.plusSeconds(300));
    }
}
