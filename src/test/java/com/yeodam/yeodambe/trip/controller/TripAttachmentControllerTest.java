package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.trip.controller.TripAttachmentController;
import com.yeodam.yeodambe.common.exception.GlobalExceptionHandler;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.service.TripAttachmentService;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TripAttachmentControllerTest {
    private final TripAttachmentService service = mock(TripAttachmentService.class);
    private final TripAttachmentController controller = new TripAttachmentController(service);

    @Test
    void multipart_attachments_필드를_여행_아이디와_함께_받는다() throws Exception {
        when(service.uploadInitialAttachments(eq(7L), eq(1L), anyList()))
                .thenReturn(completed(1));

        MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                                  NativeWebRequest request, org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
                        return jwt();
                    }
                })
                .build()
                .perform(multipart("/trips/7/initial-attachments").file(photo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_PROCESSING_STATUS_FOUND"))
                .andExpect(jsonPath("$.data.tripId").value(7))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.progress.done").value(1))
                .andExpect(jsonPath("$.data.progress.total").value(1))
                .andExpect(jsonPath("$.data.currentStep").doesNotExist())
                .andExpect(jsonPath("$.data.result.tripId").value(7))
                .andExpect(jsonPath("$.data.result.placeFolderCount").value(1))
                .andExpect(jsonPath("$.data.result.classifiedAttachmentCount").value(1))
                .andExpect(jsonPath("$.data.result.unclassifiedAttachmentCount").value(0))
                .andExpect(jsonPath("$.data.error").doesNotExist());

        verify(service).uploadInitialAttachments(eq(7L), eq(1L), argThat(files ->
                files.size() == 1 && files.getFirst().getOriginalFilename().equals("photo.jpg")));
    }

    @Test
    void 첨부가_누락되거나_비어_있으면_업로드를_시작하지_않는다() {
        for (List<MultipartFile> files : List.of(Collections.<MultipartFile>emptyList(), List.<MultipartFile>of(emptyPhoto()))) {
            var response = controller.uploadInitialAttachments(7L, jwt(), files);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().message()).isEqualTo("INVALID_ATTACHMENT_UPLOAD");
        }
        var missing = controller.uploadInitialAttachments(7L, jwt(), null);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody().message()).isEqualTo("INVALID_ATTACHMENT_UPLOAD");
        verifyNoInteractions(service);
    }

    @Test
    void 이백한_장은_서비스에_전달하지_않는다() {
        var response = controller.uploadInitialAttachments(7L, jwt(), Collections.nCopies(201, photo()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(response.getBody().message()).isEqualTo("ATTACHMENT_UPLOAD_LIMIT_EXCEEDED");
        verifyNoInteractions(service);
    }

    @Test
    void 이백_장은_서비스에_전달한다() {
        var files = Collections.<MultipartFile>nCopies(200, photo());
        when(service.uploadInitialAttachments(7L, 1L, files))
                .thenReturn(completed(200));

        var response = controller.uploadInitialAttachments(7L, jwt(), files);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data().progress().total()).isEqualTo(200);
        verify(service).uploadInitialAttachments(7L, 1L, files);
    }

    @Test
    void 결과_저장이_끝난_후_완료_응답을_반환한다() {
        var files = List.<MultipartFile>of(photo());
        when(service.uploadInitialAttachments(7L, 1L, files))
                .thenReturn(completed(1));

        var response = controller.uploadInitialAttachments(7L, jwt(), files);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().message()).isEqualTo("TRIP_PROCESSING_STATUS_FOUND");
        assertThat(response.getBody().data().tripId()).isEqualTo(7L);
        assertThat(response.getBody().data().status()).isEqualTo(ProcessingStatus.COMPLETED);
        assertThat(response.getBody().data().result().placeFolderCount()).isEqualTo(1);
    }

    @Test
    void AI가_명시적으로_실패하면_200과_FAILED_오류를_반환한다() {
        var files = List.<MultipartFile>of(photo());
        when(service.uploadInitialAttachments(7L, 1L, files))
                .thenReturn(new TripProcessingStatusResponse(
                        7L,
                        ProcessingStatus.FAILED,
                        new TripProcessingStatusResponse.Progress(12, 128),
                        null,
                        null,
                        new TripProcessingStatusResponse.Error(
                                "AI_PROCESSING_FAILED", "첨부 처리에 실패했습니다.")
                ));

        var response = controller.uploadInitialAttachments(7L, jwt(), files);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().message()).isEqualTo("TRIP_PROCESSING_STATUS_FOUND");
        assertThat(response.getBody().data().status()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(response.getBody().data().result()).isNull();
        assertThat(response.getBody().data().error().code()).isEqualTo("AI_PROCESSING_FAILED");
        assertThat(response.getBody().data().error().message()).isEqualTo("첨부 처리에 실패했습니다.");
    }

    @Test
    void multipart가_아닌_요청은_400으로_거부한다() throws Exception {
        MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build()
                .perform(post("/trips/7/initial-attachments")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_ATTACHMENT_UPLOAD"));
        verifyNoInteractions(service);
    }

    private MockMultipartFile photo() {
        return new MockMultipartFile("attachments[]", "photo.jpg", "image/jpeg", new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});
    }

    private Jwt jwt() {
        return Jwt.withTokenValue("token").header("alg", "HS256").subject("1").build();
    }

    private MockMultipartFile emptyPhoto() {
        return new MockMultipartFile("attachments[]", "empty.jpg", "image/jpeg", new byte[0]);
    }

    private TripProcessingStatusResponse completed(int total) {
        return new TripProcessingStatusResponse(
                7L,
                ProcessingStatus.COMPLETED,
                new TripProcessingStatusResponse.Progress(total, total),
                null,
                new TripProcessingStatusResponse.Result(7L, 1, total, 0),
                null
        );
    }
}
