package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.PlaceQueryProviderUnavailableException;
import com.yeodam.yeodambe.trip.client.PlaceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlaceSearchCatalogTest {
    @TempDir
    Path directory;
    private final PlaceClient client = mock(PlaceClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private RegionCatalog regions;
    private List<PlaceClient.ProviderRegion> rows;
    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        regions = new RegionCatalog(mapper);
        rows = new ArrayList<>(regions.all().stream().map(region -> new PlaceClient.ProviderRegion(
                region.code().substring(0, 2), region.code().substring(2), "000", "00", region.name()))
                .toList());
        rows.add(new PlaceClient.ProviderRegion("11", "680", "000", "00", "서울특별시 강남구"));
        rows.add(new PlaceClient.ProviderRegion("41", "117", "000", "00", "경기도 수원시 영통구"));
        rows.add(new PlaceClient.ProviderRegion("41", "117", "101", "00", "경기도 수원시 영통구 매탄동"));
        file = directory.resolve("nested/catalog.json");
        when(client.search(anyString(), anyInt())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            int page = invocation.getArgument(1);
            var matching = rows.stream().filter(row -> row.regionName().startsWith(query)).toList();
            int start = (page - 1) * 10;
            return new PlaceClient.ProviderPage(matching.size(),
                    matching.subList(start, Math.min(start + 10, matching.size())));
        });
    }

    private PlaceSearchCatalog catalog() {
        return new PlaceSearchCatalog(file.toString(), mapper, regions, client);
    }

    @Test
    void 전체_페이지를_수집하고_별칭을_파일로_저장해_재시작에도_유지한다() throws IOException {
        var catalog = catalog();
        assertThat(catalog.isReady()).isFalse();
        assertThatThrownBy(catalog::entries).isInstanceOf(PlaceQueryProviderUnavailableException.class);
        catalog.refresh();

        assertThat(catalog.entries()).contains(
                new PlaceSearchCatalog.Entry("서울특별시 강남구", "11000"),
                new PlaceSearchCatalog.Entry("경기도 수원시 영통구", "41110"),
                new PlaceSearchCatalog.Entry("부산광역시 기장군", "26710"));
        assertThat(catalog.entries()).noneMatch(entry -> entry.searchName().endsWith("매탄동"));
        verify(client).search("경기도", 2);
        String json = Files.readString(file);
        assertThat(json).contains("\n  \"schemaVersion\"", "\n  \"entries\"");
        assertThat(mapper.readTree(json).path("schemaVersion").asInt()).isEqualTo(1);
        var saved = catalog.entries();
        clearInvocations(client);

        assertThat(catalog().entries()).containsExactlyElementsOf(saved);
        verifyNoInteractions(client);
    }

    @Test
    void 중간_페이지_실패시_기존_파일과_메모리를_유지한다() throws IOException {
        var catalog = catalog();
        catalog.refresh();
        var saved = Files.readString(file);
        var entries = catalog.entries();
        when(client.search("경기도", 2)).thenThrow(new PlaceQueryProviderUnavailableException("장애"));

        assertThatThrownBy(catalog::refresh).isInstanceOf(PlaceQueryProviderUnavailableException.class);
        assertThat(Files.readString(file)).isEqualTo(saved);
        assertThat(catalog.entries()).isSameAs(entries);
    }

    @Test
    void 수집된_지역이_누락되거나_페이지가_비어있으면_교체하지_않는다() throws IOException {
        var catalog = catalog();
        catalog.refresh();
        var saved = Files.readString(file);
        rows.removeIf(row -> "제주특별자치도 서귀포시".equals(row.regionName()));

        assertThatThrownBy(catalog::refresh).isInstanceOf(PlaceQueryProviderUnavailableException.class);
        assertThat(Files.readString(file)).isEqualTo(saved);

        when(client.search("경기도", 2)).thenReturn(new PlaceClient.ProviderPage(100, List.of()));
        assertThatThrownBy(catalog::refresh).isInstanceOf(PlaceQueryProviderUnavailableException.class);
        assertThat(Files.readString(file)).isEqualTo(saved);
    }

    @Test
    void 파일_저장_실패시_신규_스냅샷을_공개하지_않는다() throws IOException {
        var catalog = catalog();
        catalog.refresh();
        var entries = catalog.entries();
        Files.delete(file);
        Files.createDirectory(file);

        assertThatThrownBy(catalog::refresh).isInstanceOf(UncheckedIOException.class)
                .hasMessage("여행지 검색 파일 저장에 실패했습니다.");
        assertThat(catalog.entries()).isSameAs(entries);
    }

    @Test
    void 손상되거나_불완전한_파일은_준비된_데이터로_사용하지_않는다() throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "not-json");
        assertThat(catalog().isReady()).isFalse();
        Files.writeString(file, """
                {"schemaVersion":1,"updatedAt":"2026-10-01T00:00:00Z", "entries":[]}
                """);
        assertThat(catalog().isReady()).isFalse();
    }

    @Test
    void 파일의_검색용_이름과_선택_지역이_불일치하면_적재하지_않는다() throws IOException {
        catalog().refresh();
        var saved = mapper.readTree(Files.readString(file));
        for (var entry : saved.path("entries")) {
            if (entry.path("searchName").asString().equals("서울특별시 강남구")) {
                ((ObjectNode) entry).put("regionCode", "41110");
            }
        }
        Files.writeString(file, mapper.writeValueAsString(saved));

        assertThat(catalog().isReady()).isFalse();
    }
}
