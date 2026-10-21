package com.yeodam.yeodambe.user.migration;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import com.yeodam.yeodambe.trip.migration.MigrationTripMode;
import com.yeodam.yeodambe.user.security.TokenHasher;
import tools.jackson.databind.ObjectMapper;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class MigrationAuthRecoveryGatewayTest {
 @Test void committed_result_is_recovered_without_queueing_or_rotation() {
  KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
  var apply=mock(MigrationAuthApplyService.class);var redis=mock(MigrationAuthRedis.class);
  var reply=new MigrationAuthReply("id","refresh",200,"access","new",null);
  when(apply.recover(any())).thenReturn(Optional.of(reply));when(redis.applied(any(),eq(reply))).thenReturn(true);
  var gateway=new MigrationAuthGateway(kafka,mock(MigrationAuthCrypto.class),mock(ObjectMapper.class),mock(MigrationTripMode.class),new TokenHasher(),apply,redis,"topic");
  assertThat(gateway.request("refresh","old")).isEqualTo(reply);
  verifyNoInteractions(kafka);verify(apply,never()).apply(any());
 }
 @Test void direct_response_loss_reuses_committed_reply_without_kafka_or_redis_wait() {
  KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
  var apply=mock(MigrationAuthApplyService.class);var redis=mock(MigrationAuthRedis.class);
  var reply=new MigrationAuthReply("id","refresh",200,"access","new",null);
  when(apply.recover(any())).thenReturn(Optional.empty(),Optional.of(reply));when(apply.apply(any())).thenReturn(reply);
  var gateway=new MigrationAuthGateway(kafka,mock(MigrationAuthCrypto.class),mock(ObjectMapper.class),mock(MigrationTripMode.class),new TokenHasher(),apply,redis,"topic");
  assertThat(gateway.direct("refresh","old")).isEqualTo(reply);
  assertThat(gateway.direct("refresh","old")).isEqualTo(reply);
  verify(apply,times(1)).apply(any());verifyNoInteractions(kafka,redis);
 }
 @Test void direct_retry_claim_race_recovers_after_other_transaction_commits() {
  var apply=mock(MigrationAuthApplyService.class);
  var reply=new MigrationAuthReply("id","refresh",200,"access","new",null);
  when(apply.recover(any())).thenReturn(Optional.empty(),Optional.of(reply));
  when(apply.apply(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("claim"));
  var gateway=new MigrationAuthGateway(mock(KafkaTemplate.class),mock(MigrationAuthCrypto.class),mock(ObjectMapper.class),mock(MigrationTripMode.class),new TokenHasher(),apply,mock(MigrationAuthRedis.class),"topic");
  assertThat(gateway.direct("refresh","old")).isEqualTo(reply);
 }
 @Test void direct_expired_reply_cannot_rotate_again() {
  var apply=mock(MigrationAuthApplyService.class);
  when(apply.recover(any())).thenThrow(new com.yeodam.yeodambe.user.exception.RefreshTokenInvalidOrExpiredException());
  var gateway=new MigrationAuthGateway(mock(KafkaTemplate.class),mock(MigrationAuthCrypto.class),mock(ObjectMapper.class),mock(MigrationTripMode.class),new TokenHasher(),apply,mock(MigrationAuthRedis.class),"topic");
  assertThatThrownBy(()->gateway.direct("refresh","old")).isInstanceOf(com.yeodam.yeodambe.user.exception.RefreshTokenInvalidOrExpiredException.class);
  verify(apply,never()).apply(any());
 }
}
