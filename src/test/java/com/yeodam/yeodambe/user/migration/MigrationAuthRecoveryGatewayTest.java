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
}
