package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.common.exception.TripNameDuplicatedException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MigrationTripConsumerTest {
    private static final String ID = "51961261-7761-4587-ae2b-55c0452924bd";
    private final ObjectMapper mapper = mock(ObjectMapper.class);
    private final MigrationTripAdmissionService admission = mock(MigrationTripAdmissionService.class);
    private final MigrationTripApplyService apply = mock(MigrationTripApplyService.class);
    private final MigrationTripBatchService batches = mock(MigrationTripBatchService.class);
    private final MigrationTripConsumer consumer = new MigrationTripConsumer(mapper, admission, apply, batches);
    private final MigrationTripCommand command = new MigrationTripCommand(
            ID, 7L, "hash", "강릉", LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 2), List.of("42150"));

    @Test
    void key나_hash가_틀린_레코드는_적용하지_않는다() {
        when(mapper.readValue("json", MigrationTripCommand.class)).thenReturn(command);
        when(admission.hashPayload(7L, command.tripRequest())).thenReturn("hash");

        assertThatThrownBy(() -> consumer.consume("json", "other-id"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(apply);
    }

    @Test
    void 영구_비즈니스_실패는_결과로_남긴다() {
        when(mapper.readValue("json", MigrationTripCommand.class)).thenReturn(command);
        when(admission.hashPayload(7L, command.tripRequest())).thenReturn("hash");
        doThrow(new TripNameDuplicatedException()).when(apply).apply(command);

        consumer.consume("json", "7");

        verify(apply).recordFailure(command, "TRIP_NAME_DUPLICATED");
    }

    private MigrationTripCommand decode(String json, String id) {
        var next = new MigrationTripCommand(id, 7L, "hash", json, command.startDate(),
                command.endDate(), command.regionCodes());
        when(mapper.readValue(json, MigrationTripCommand.class)).thenReturn(next);
        when(admission.hashPayload(7L, next.tripRequest())).thenReturn("hash");
        return next;
    }

    @Test
    void 묶음의_뒤쪽이_잘못돼도_앞부분부터_적용하지_않는다() {
        decode("first", "one");
        decode("bad", "two");
        assertThatThrownBy(() -> consumer.consumeBatch(List.of(
                new ConsumerRecord<>("trips", 0, 0, "7", "first"),
                new ConsumerRecord<>("trips", 0, 1, "wrong", "bad"))))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(apply, batches);
    }

    @Test
    void 같은_파티션은_입력순서대로_묶고_다른_파티션은_분리한다() {
        var first = decode("first", "one");
        var second = decode("second", "two");
        var other = decode("other", "three");
        when(batches.applyBatch(anyList())).thenAnswer(i -> i.getArgument(0));
        consumer.consumeBatch(List.of(
                new ConsumerRecord<>("trips", 0, 1, "7", "first"),
                new ConsumerRecord<>("trips", 1, 4, "7", "other"),
                new ConsumerRecord<>("trips", 0, 2, "7", "second")));
        var ordered = inOrder(batches);
        ordered.verify(batches).applyBatch(List.of(first, second));
        ordered.verify(batches).applyBatch(List.of(other));
        verifyNoInteractions(apply);
    }

    @Test
    void 업무실패는_묶음_롤백후_원래순서대로_단건_결과를_남긴다() {
        var first = decode("first", "one");
        var second = decode("second", "two");
        when(batches.applyBatch(List.of(first, second))).thenThrow(new TripNameDuplicatedException());
        when(apply.apply(first)).thenReturn(true);
        when(apply.apply(second)).thenThrow(new TripNameDuplicatedException());
        consumer.consumeBatch(List.of(
                new ConsumerRecord<>("trips", 0, 1, "7", "first"),
                new ConsumerRecord<>("trips", 0, 2, "7", "second")));
        var ordered = inOrder(batches, apply);
        ordered.verify(batches).applyBatch(List.of(first, second));
        ordered.verify(apply).apply(first);
        ordered.verify(apply).apply(second);
        ordered.verify(apply).recordFailure(second, "TRIP_NAME_DUPLICATED");
    }

    @Test
    void DB운영오류는_단건우회하거나_성공으로_삼키지_않는다() {
        var first = decode("first", "one");
        when(batches.applyBatch(List.of(first))).thenThrow(new IllegalStateException("DB unavailable"));
        assertThatThrownBy(() -> consumer.consumeBatch(List.of(
                new ConsumerRecord<>("trips", 0, 1, "7", "first"))))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(apply);
    }

    @Test
    void 열건초과와_역전된Offset은_적용하지_않는다() {
        var record = new ConsumerRecord<>("trips", 0, 1L, "7", "first");
        assertThatThrownBy(() -> consumer.consumeBatch(java.util.Collections.nCopies(11, record)))
                .isInstanceOf(IllegalStateException.class);
        decode("first", "one");
        assertThatThrownBy(() -> consumer.consumeBatch(List.of(record, record)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(apply, batches);
    }
}
