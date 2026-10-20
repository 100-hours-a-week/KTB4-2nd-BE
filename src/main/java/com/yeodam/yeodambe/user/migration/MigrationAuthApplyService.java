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
    @Value("${migration.auth.recovery-seconds:120}")
    private int recoverySeconds = 120;

    @Transactional(readOnly=true)
    public java.util.Optional<MigrationAuthReply> recover(MigrationAuthCommand c) {
        var rows=jdbc.query("SELECT encrypted_result,(created_at>=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP)) FROM migration_auth_request_result WHERE request_id=?",
            (rs,n)->new Object[]{rs.getString(1),rs.getBoolean(2)},-recoverySeconds,c.requestId());
        if(rows.isEmpty()) return java.util.Optional.empty();
        var row=rows.getFirst();
        if(c.operation().equals("csrf") && (!(Boolean)row[1] || !current(c,mapper.readValue(crypto.decrypt((String)row[0],topic+".journal"),MigrationAuthReply.class)))) return java.util.Optional.empty();
        if(!(Boolean)row[1])
            throw new com.yeodam.yeodambe.user.exception.RefreshTokenInvalidOrExpiredException();
        var cached=mapper.readValue(crypto.decrypt((String)row[0],topic+".journal"),MigrationAuthReply.class);
        if(!current(c,cached)) throw new com.yeodam.yeodambe.user.exception.RefreshTokenInvalidOrExpiredException();
        return java.util.Optional.of(cached);
    }

    @Transactional(readOnly=true)
    public String routingKey(MigrationAuthCommand c) {
        if(c.operation().equals("csrf")) return hasher.hash("csrf:"+c.credential());
        var sids=jdbc.query("SELECT sid FROM login_sessions WHERE refresh_token_hash=? AND expires_at>CURRENT_TIMESTAMP",
            (rs,n)->rs.getString(1),hasher.hash(c.credential()));
        if(sids.isEmpty()) {
            var old=recover(c);
            if(old.isPresent()) sids=jdbc.query("SELECT sid FROM login_sessions WHERE refresh_token_hash=? AND expires_at>CURRENT_TIMESTAMP",
                (rs,n)->rs.getString(1),hasher.hash(old.get().refreshToken()));
        }
        return hasher.hash("refresh:"+(sids.isEmpty()?c.credential():sids.getFirst()));
    }

    private boolean current(MigrationAuthCommand c,MigrationAuthReply cached) {
        return (c.operation().equals("refresh")
            ? jdbc.queryForObject("SELECT COUNT(*) FROM login_sessions WHERE refresh_token_hash=? AND expires_at>CURRENT_TIMESTAMP",Integer.class,hasher.hash(cached.refreshToken()))
            : jdbc.queryForObject("SELECT COUNT(*) FROM csrf_tokens WHERE browser_context_hash=? AND token_value=? AND expires_at>CURRENT_TIMESTAMP",Integer.class,hasher.hash(c.credential()),cached.csrfToken()))==1;
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
            if(valid!=1) {
                if(!c.operation().equals("csrf")) throw new com.yeodam.yeodambe.user.exception.RefreshTokenInvalidOrExpiredException();
                // CSRF issuance is public and may legitimately recur after logout/expiry.
                var renewed=new MigrationAuthReply(c.requestId(),c.operation(),200,null,null,csrf.issue(c.credential()));
                jdbc.update("UPDATE migration_auth_request_result SET encrypted_result=? WHERE request_id=?",
                    crypto.encrypt(mapper.writeValueAsString(renewed),topic+".journal"),c.requestId());
                return renewed;
            }
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
