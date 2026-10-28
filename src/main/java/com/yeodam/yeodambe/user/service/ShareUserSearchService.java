package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.common.exception.UserNotFoundException;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.service.response.UserSearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShareUserSearchService {
    private final UserRepository userRepository;

    public UserSearchResponse searchByEmail(String email, Long requesterId) {
        User user = userRepository.findByEmailAndDeletedAtIsNull(email)
                .filter(found -> !found.getUserId().equals(requesterId))
                .orElseThrow(UserNotFoundException::new);
        return new UserSearchResponse(user.getUserId(), user.getNickname(), user.getEmail());
    }

}
