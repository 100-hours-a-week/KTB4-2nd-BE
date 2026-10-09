package com.yeodam.yeodambe.search.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.common.exception.InvalidSearchQueryException;
import com.yeodam.yeodambe.search.service.SearchService;
import com.yeodam.yeodambe.search.service.request.SearchRequest;
import com.yeodam.yeodambe.search.service.response.SearchResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SearchController {
    private final SearchService searchService;

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<SearchResponse>> search(
            @Valid @ModelAttribute SearchRequest request,
            BindingResult bindingResult,
            @AuthenticationPrincipal Jwt jwt
    ) {
        if (bindingResult.hasErrors()) {
            throw new InvalidSearchQueryException();
        }
        Long userId = Long.valueOf(jwt.getSubject());
        SearchResponse response = searchService.search(userId, request.query());
        return ResponseEntity.ok(new ApiResponse<>(SuccessMessage.SEARCH_SUCCESS, response));
    }
}
