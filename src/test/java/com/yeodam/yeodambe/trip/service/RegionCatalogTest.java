package com.yeodam.yeodambe.trip.service;

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

    @Test
    void 구는_상위_시도로_통합하고_군은_유지한다() throws Exception {
        RegionCatalog catalog = new RegionCatalog(new ObjectMapper());

        assertThat(catalog.getRequired("11000").name()).isEqualTo("서울특별시");
        assertThat(catalog.getRequired("12000").name()).isEqualTo("전남광주통합특별시");
        assertThat(catalog.getRequired("26710").name()).isEqualTo("부산광역시 기장군");
        assertThrows(InvalidTripRequestException.class, () -> catalog.getRequired("11110"));
    }
}
