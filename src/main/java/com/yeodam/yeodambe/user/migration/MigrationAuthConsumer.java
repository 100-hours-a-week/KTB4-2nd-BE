package com.yeodam.yeodambe.user.migration;

import com.yeodam.yeodambe.user.exception.RefreshTokenInvalidOrExpiredException;
import com.yeodam.yeodambe.user.security.TokenHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name="migration.auth.enabled", havingValue="true")
public class MigrationAuthConsumer {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(MigrationAuthConsumer.class);
    private final MigrationAuthApplyService apply;
    private final MigrationAuthCrypto crypto;
    private final MigrationAuthRedis redis;
    private final KafkaTemplate<String,String> kafka;
    private final ObjectMapper mapper;
    private final TokenHasher hasher;
    private final String topic;
    public MigrationAuthConsumer(MigrationAuthApplyService apply,MigrationAuthCrypto crypto,MigrationAuthRedis redis,
            KafkaTemplate<String,String> kafka,ObjectMapper mapper,TokenHasher hasher,@Value("${migration.auth.topic}") String topic) {
        this.apply=apply;this.crypto=crypto;this.redis=redis;this.kafka=kafka;this.mapper=mapper;this.hasher=hasher;this.topic=topic;
    }
    @KafkaListener(topics="${migration.auth.topic}",groupId="${migration.auth.consumer-group}",
        containerFactory="migrationAuthListenerFactory",autoStartup="${migration.auth.consumer-enabled:false}")
    public void consume(String value) throws Exception {
        var c=mapper.readValue(crypto.decrypt(value,topic),MigrationAuthCommand.class);
        if(!hasher.hash(topic+":"+c.operation()+":"+c.credential()).equals(c.requestId()))
            throw new IllegalArgumentException("Auth request integrity rejected");
        if(System.currentTimeMillis()-c.createdAt()>1800000 || c.createdAt()>System.currentTimeMillis()+30000)
            throw new IllegalStateException("Auth request age rejected; preserve offset");
        MigrationAuthReply reply;
        try { reply=apply.apply(c); }
        catch(RefreshTokenInvalidOrExpiredException invalid) {
            reply=new MigrationAuthReply(c.requestId(),c.operation(),401,null,null,null);
        } catch(RuntimeException failure) {
            log.warn("migration_auth_apply_failed operation={} type={}",c.operation(),failure.getClass().getSimpleName());
            throw failure;
        }
        if(reply.status()==200) {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!redis.applied(c,reply)) {
                if(System.nanoTime()>deadline) throw new IllegalStateException("Auth CDC confirmation pending");
                Thread.sleep(50);
            }
        }
        kafka.send(topic+".results",c.requestId(),crypto.encrypt(mapper.writeValueAsString(reply),topic+".results")).get(5,TimeUnit.SECONDS);
        log.info("migration_auth_result operation={} status={}",c.operation(),reply.status());
    }
}
