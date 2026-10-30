package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.common.exception.PlaceQueryProviderUnavailableException;
import com.yeodam.yeodambe.trip.client.PlaceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class PlaceSearchCatalog {
    private final Path file;
    private final ObjectMapper objectMapper;
    private final RegionCatalog regions;
    private final PlaceClient client;
    private volatile List<Entry> entries;

    public PlaceSearchCatalog(
            @Value("${place.catalog.path}") String path,
            ObjectMapper objectMapper,
            RegionCatalog regions,
            PlaceClient client
    ) {
        this.file = Path.of(path).toAbsolutePath();
        this.objectMapper = objectMapper;
        this.regions = regions;
        this.client = client;
        if (Files.exists(file)) {
            try {
                Snapshot saved = objectMapper.readValue(Files.readString(file), Snapshot.class);
                validate(saved);
                entries = List.copyOf(saved.entries());
            } catch (IOException | RuntimeException e) {
                log.warn("여행지 검색 파일을 적재하지 못했습니다. path={}", file, e);
            }
        }
    }

    public boolean isReady() {
        return entries != null;
    }

    public List<Entry> entries() {
        List<Entry> current = entries;
        if (current == null) {
            throw new PlaceQueryProviderUnavailableException(TripInternalErrorMessage.PLACE_CATALOG_NOT_READY.message());
        }
        return current;
    }

    public synchronized void refresh() {
        List<Entry> collected = collectEntries();
        Snapshot updated = new Snapshot(1, Instant.now().toString(), collected);
        validate(updated);
        save(updated);
        entries = updated.entries();
        log.info("여행지 검색 데이터를 갱신했습니다. entries={}, updatedAt={}",
                entries.size(), updated.updatedAt());
    }

    private List<Entry> collectEntries() {
        Set<Entry> collected = new LinkedHashSet<>();
        List<String> queries = regions.all().stream()
                .map(region -> region.name().split(" ", 2)[0])
                .distinct()
                .sorted()
                .toList();
        for (String query : queries) {
            collectPages(query, collected);
        }
        Set<String> collectedCodes = new HashSet<>();
        collected.forEach(entry -> collectedCodes.add(entry.regionCode()));
        for (RegionCatalog.Region region : regions.all()) {
            if (!collectedCodes.contains(region.code())) {
                throw new PlaceQueryProviderUnavailableException(TripInternalErrorMessage.PLACE_COLLECTION_REGION_MISSING.message());
            }
            collected.add(new Entry(region.name(), region.code()));
        }
        return List.copyOf(collected);
    }

    private void collectPages(String query, Set<Entry> collected) {
        PlaceClient.ProviderPage firstPage = client.search(query, 1);
        int totalCount = firstPage.totalCount();
        if (totalCount < 0) {
            throw new PlaceQueryProviderUnavailableException(TripInternalErrorMessage.PLACE_COLLECTION_COUNT_INVALID.message());
        }
        int totalPages = (int) Math.max(1, (totalCount + (long) PlaceClient.PAGE_SIZE - 1)
                / PlaceClient.PAGE_SIZE);
        for (int pageNo = 1; pageNo <= totalPages; pageNo++) {
            PlaceClient.ProviderPage page = pageNo == 1 ? firstPage : client.search(query, pageNo);
            int expectedRows = Math.min(PlaceClient.PAGE_SIZE,
                    totalCount - (pageNo - 1) * PlaceClient.PAGE_SIZE);
            if (page.totalCount() != totalCount || page.rows().size() != expectedRows) {
                throw new PlaceQueryProviderUnavailableException(
                        TripInternalErrorMessage.PLACE_COLLECTION_PAGE_INCOMPLETE.message().formatted(query, pageNo));
            }
            for (PlaceClient.ProviderRegion row : page.rows()) {
                collect(row, collected);
            }
        }
    }

    private void collect(PlaceClient.ProviderRegion row, Set<Entry> collected) {
        if (!isNumericCode(row.sidoCd(), 2) || !isNumericCode(row.sggCd(), 3)
                || !"000".equals(row.umdCd()) || !"00".equals(row.riCd())
                || row.regionName() == null || row.regionName().isBlank()) {
            return;
        }
        RegionCatalog.Region region = regions.findByName(selectedName(row.regionName()));
        if (region != null && region.code().startsWith(row.sidoCd())
                && (row.regionName().endsWith("구") || region.code().equals(row.sidoCd() + row.sggCd()))) {
            collected.add(new Entry(row.regionName(), region.code()));
        }
    }

    private boolean isNumericCode(String value, int length) {
        return value != null && value.length() == length && value.chars().allMatch(Character::isDigit);
    }

    private String selectedName(String name) {
        if (!name.endsWith("구")) {
            return name;
        }
        int lastSpace = name.lastIndexOf(' ');
        return lastSpace < 0 ? null : name.substring(0, lastSpace);
    }

    private void validate(Snapshot snapshot) {
        if (snapshot == null || snapshot.schemaVersion() != 1 || snapshot.updatedAt() == null
                || snapshot.entries() == null || snapshot.entries().isEmpty()) {
            throw new IllegalStateException(TripInternalErrorMessage.PLACE_CATALOG_FORMAT_INVALID.message());
        }
        Instant.parse(snapshot.updatedAt());
        Set<String> codes = new HashSet<>();
        for (Entry entry : snapshot.entries()) {
            if (entry == null || entry.searchName() == null || entry.searchName().isBlank()
                    || entry.regionCode() == null) {
                throw new IllegalStateException(TripInternalErrorMessage.PLACE_CATALOG_ENTRY_INVALID.message());
            }
            RegionCatalog.Region region = regions.getRequired(entry.regionCode());
            if (!region.name().equals(selectedName(entry.searchName()))) {
                throw new IllegalStateException(TripInternalErrorMessage.PLACE_CATALOG_REGION_MISMATCH.message());
            }
            codes.add(entry.regionCode());
        }
        if (!regions.all().stream().allMatch(region -> codes.contains(region.code()))) {
            throw new IllegalStateException(TripInternalErrorMessage.PLACE_CATALOG_REGION_MISSING.message());
        }
    }

    private void save(Snapshot snapshot) {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "place-search-", ".json.tmp");
            Files.writeString(temporary, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(TripInternalErrorMessage.PLACE_CATALOG_WRITE_FAILED.message(), e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    log.warn("여행지 검색 임시 파일 정리에 실패했습니다. path={}", temporary, e);
                }
            }
        }
    }

    public record Entry(String searchName, String regionCode) {
    }

    private record Snapshot(int schemaVersion, String updatedAt, List<Entry> entries) {
    }
}
