package com.yeodam.yeodambe.trip;

import com.yeodam.yeodambe.common.exception.InvalidTripRequestException;
import com.yeodam.yeodambe.trip.service.RegionCatalog;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegionCatalogTest {

    @Test
    void 지역_목록을_읽고_없는_코드를_거부한다() throws Exception {
        RegionCatalog catalog = new RegionCatalog(new ObjectMapper());

        assertThat(catalog.getRequired("50110").name()).isEqualTo("제주특별자치도 제주시");
        assertThrows(InvalidTripRequestException.class, () -> catalog.getRequired("99999"));
    }
}
