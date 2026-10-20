package com.yeodam.yeodambe.user.migration;

import com.yeodam.yeodambe.user.service.AccessTokenRefreshService;
import com.yeodam.yeodambe.user.service.CsrfTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name="migration.auth.enabled", havingValue="true")
public class MigrationAuthApplyService {
    private final JdbcTemplate jdbc;
    private final AccessTokenRefreshService refresh;
    private final CsrfTokenService csrf;
    private final ObjectMapper mapper;
    private final MigrationAuthCrypto crypto;
    private final String topic;
    private final com.yeodam.yeodambe.user.security.TokenHasher hasher;
    public MigrationAuthApplyService(JdbcTemplate jdbc,AccessTokenRefreshService refresh,CsrfTokenService csrf,
            ObjectMapper mapper,MigrationAuthCrypto crypto,com.yeodam.yeodambe.user.security.TokenHasher hasher,@Value("${migration.auth.topic}") String topic) {
        this.jdbc=jdbc;this.refresh=refresh;this.csrf=csrf;this.mapper=mapper;this.crypto=crypto;this.topic=topic;this.hasher=hasher;
    }
    @Transactional
    public MigrationAuthReply apply(MigrationAuthCommand c) {
        var old=jdbc.query("SELECT encrypted_result FROM migration_auth_request_result WHERE request_id=?",
            (rs,n)->rs.getString(1),c.requestId());
        if(!old.isEmpty()) {
            var cached=mapper.readValue(crypto.decrypt(old.getFirst(),topic+".journal"),MigrationAuthReply.class);
            int valid=c.operation().equals("refresh")
                ? jdbc.queryForObject("SELECT COUNT(*) FROM login_sessions WHERE refresh_token_hash=? AND expires_at>CURRENT_TIMESTAMP",Integer.class,hasher.hash(cached.refreshToken()))
                : jdbc.queryForObject("SELECT COUNT(*) FROM csrf_tokens WHERE browser_context_hash=? AND token_value=? AND expires_at>CURRENT_TIMESTAMP",Integer.class,hasher.hash(c.credential()),cached.csrfToken());
            if(valid!=1) throw new com.yeodam.yeodambe.user.exception.RefreshTokenInvalidOrExpiredException();
            return cached;
        }
        // This claim and the session mutation commit atomically; replay never rotates twice.
        jdbc.update("INSERT INTO migration_auth_request_result(request_id,encrypted_result) VALUES (?, '')",c.requestId());
        MigrationAuthReply reply;
        if(c.operation().equals("refresh")) {
            var result=refresh.refresh(c.credential());
            reply=new MigrationAuthReply(c.requestId(),c.operation(),200,result.accessToken(),result.refreshToken(),null);
        } else if(c.operation().equals("csrf")) {
            reply=new MigrationAuthReply(c.requestId(),c.operation(),200,null,null,csrf.issue(c.credential()));
        } else throw new IllegalArgumentException("Unsupported auth operation");
        jdbc.update("UPDATE migration_auth_request_result SET encrypted_result=? WHERE request_id=?",
            crypto.encrypt(mapper.writeValueAsString(reply),topic+".journal"),c.requestId());
        return reply;
    }
}
