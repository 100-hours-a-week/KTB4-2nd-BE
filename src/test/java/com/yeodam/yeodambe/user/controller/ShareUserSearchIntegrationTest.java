package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ShareUserSearchIntegrationTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private JdbcTemplate jdbc;
    private Long ownerId;
    private Long targetId;
    private String targetEmail;

    @BeforeEach
    void prepare() {
        ownerId = users.save(new User(UUID.randomUUID() + "@test.com", "소유자")).getUserId();
        targetEmail = "Friend+" + UUID.randomUUID() + "@test.com";
        targetId = users.save(new User(targetEmail, "여행친구")).getUserId();
    }

    @Test
    void 이메일을_정확히_조회하고_공개_필드만_반환한다() throws Exception {
        mvc.perform(search(targetEmail))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("USER_FOUND"))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data.userId").value(targetId))
                .andExpect(jsonPath("$.data.nickname").value("여행친구"))
                .andExpect(jsonPath("$.data.email").value(targetEmail));
        mvc.perform(search(targetEmail.toLowerCase()))
                .andExpect(status().isNotFound());
        mvc.perform(search(targetEmail.substring(1)))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not-an-email"})
    void 잘못된_이메일은_전용_오류로_거절한다(String email) throws Exception {
        mvc.perform(search(email)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_EMAIL_FORMAT"));
    }

    @Test
    void 누락과_길이초과_이메일을_거절한다() throws Exception {
        mvc.perform(get("/users/search").with(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_EMAIL_FORMAT"));
        mvc.perform(search("a".repeat(250) + "@test.com"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_EMAIL_FORMAT"));
    }

    @Test
    void 본인과_탈퇴회원은_제외하고_재가입은_새_ID를_반환한다() throws Exception {
        mvc.perform(search(users.findById(ownerId).orElseThrow().getEmail()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("USER_NOT_FOUND"));
        jdbc.update("UPDATE users SET deleted_at = NOW(6) WHERE user_id = ?", targetId);
        mvc.perform(search(targetEmail)).andExpect(status().isNotFound());
        Long newId = users.save(new User(targetEmail, "재가입")).getUserId();
        mvc.perform(search(targetEmail)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(newId));
    }

    @Test
    void 미인증_이메일_검색을_거절한다() throws Exception {
        mvc.perform(get("/users/search").param("email", targetEmail))
                .andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder search(String email) {
        return get("/users/search").param("email", email)
                .with(jwt().jwt(token -> token.subject(ownerId.toString())));
    }
}
