package com.yeodam.yeodambe.user.repository;

import com.yeodam.yeodambe.user.entity.OAuthAccount;
import com.yeodam.yeodambe.user.entity.OAuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, Long> {

    Optional<OAuthAccount> findByProviderAndProviderUserIdAndDeletedAtIsNull(
            OAuthProvider provider,
            String providerUserId
    );

    boolean existsByProviderAndProviderUserIdAndDeletedAtIsNull(
            OAuthProvider provider,
            String providerUserId
    );
}
