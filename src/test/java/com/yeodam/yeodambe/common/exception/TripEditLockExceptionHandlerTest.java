package com.yeodam.yeodambe.common.exception;

import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException;
import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TripEditLockExceptionHandlerTest {
    private final MockMvc mvc = standaloneSetup(new LockController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void 락_경합은_업무_거절과_구분한_429_응답을_반환한다() throws Exception {
        mvc.perform(get("/lock/{reason}", Reason.CONTENDED))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("TRIP_EDIT_BUSY"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @ParameterizedTest
    @EnumSource(value = Reason.class, names = {"STORE_UNAVAILABLE", "INTERRUPTED"})
    void 저장소_장애와_중단은_503_응답을_반환한다(Reason reason) throws Exception {
        mvc.perform(get("/lock/{reason}", reason))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("TRIP_EDIT_LOCK_UNAVAILABLE"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @RestController
    static class LockController {
        @GetMapping("/lock/{reason}")
        public void reject(@PathVariable Reason reason) {
            throw new TripEditLockRejectedException(reason);
        }
    }
}
