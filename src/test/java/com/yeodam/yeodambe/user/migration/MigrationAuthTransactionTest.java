package com.yeodam.yeodambe.user.migration;
import com.yeodam.yeodambe.user.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
@JdbcTest(properties={"migration.auth.enabled=true","migration.auth.topic=test"})
@ActiveProfiles("local")
@Import({MigrationAuthApplyService.class,com.yeodam.yeodambe.user.security.TokenHasher.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class MigrationAuthTransactionTest {
 @Autowired JdbcTemplate jdbc; @Autowired MigrationAuthApplyService apply;
 @MockitoBean AccessTokenRefreshService refresh; @MockitoBean CsrfTokenService csrf;
 @MockitoBean MigrationAuthCrypto crypto; @MockitoBean ObjectMapper mapper;
 MigrationAuthCommand command=new MigrationAuthCommand("id","refresh","old",System.currentTimeMillis());
 MigrationAuthReply reply=new MigrationAuthReply("id","refresh",200,"access","new",null);
 @BeforeEach void schema() {
  jdbc.execute("CREATE TABLE IF NOT EXISTS migration_auth_request_result(request_id VARCHAR(64) PRIMARY KEY,encrypted_result TEXT NOT NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
  jdbc.execute("DELETE FROM migration_auth_request_result");
  jdbc.execute("CREATE TABLE IF NOT EXISTS login_sessions(refresh_token_hash VARCHAR(64),expires_at TIMESTAMP,sid VARCHAR(64))");
  jdbc.execute("DELETE FROM login_sessions");
  jdbc.update("INSERT INTO login_sessions VALUES (?,DATEADD('DAY',1,CURRENT_TIMESTAMP),'session')",new com.yeodam.yeodambe.user.security.TokenHasher().hash("new"));
  when(refresh.refresh("old")).thenReturn(new AccessTokenRefreshService.Result("access","new"));
  when(mapper.writeValueAsString(any())).thenReturn("json");when(crypto.encrypt("json","test.journal")).thenReturn("cipher");
  when(crypto.decrypt("cipher","test.journal")).thenReturn("json");when(mapper.readValue("json",MigrationAuthReply.class)).thenReturn(reply);
 }
 @Test void replay_returns_same_result_without_second_rotation() {
  apply.apply(command);apply.apply(command);verify(refresh,times(1)).refresh("old");
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM migration_auth_request_result",Integer.class)).isEqualTo(1);
 }
 @Test void logout_then_replay_does_not_resurrect_session() {
  apply.apply(command);jdbc.execute("DELETE FROM login_sessions");
  assertThatThrownBy(()->apply.apply(command)).isInstanceOf(com.yeodam.yeodambe.user.exception.RefreshTokenInvalidOrExpiredException.class);
  verify(refresh,times(1)).refresh("old");
 }
 @Test void refresh_rotation_keeps_same_partition_key() {
  var h=new com.yeodam.yeodambe.user.security.TokenHasher();
  jdbc.update("UPDATE login_sessions SET refresh_token_hash=?",h.hash("old"));
  String before=apply.routingKey(command);
  when(refresh.refresh("old")).thenAnswer(invocation->{
   jdbc.update("UPDATE login_sessions SET refresh_token_hash=?",h.hash("new"));
   return new AccessTokenRefreshService.Result("access","new");
  });
  apply.apply(command);
  assertThat(apply.routingKey(command)).isEqualTo(before);
  assertThat(apply.routingKey(new MigrationAuthCommand("next","refresh","new",System.currentTimeMillis()))).isEqualTo(before);
 }
 @Test void lost_http_response_can_recover_without_second_rotation() {
  apply.apply(command);assertThat(apply.recover(command)).contains(reply);verify(refresh,times(1)).refresh("old");
 }
 @Test void old_result_after_next_rotation_is_rejected() {
  apply.apply(command);jdbc.update("UPDATE login_sessions SET refresh_token_hash='later'");
  assertThatThrownBy(()->apply.recover(command)).isInstanceOf(com.yeodam.yeodambe.user.exception.RefreshTokenInvalidOrExpiredException.class);
 }
 @Test void expired_recovery_window_is_rejected() {
  apply.apply(command);jdbc.execute("UPDATE migration_auth_request_result SET created_at=DATEADD('SECOND',-121,CURRENT_TIMESTAMP)");
  assertThatThrownBy(()->apply.recover(command)).isInstanceOf(com.yeodam.yeodambe.user.exception.RefreshTokenInvalidOrExpiredException.class);
 }
 @Test void failed_rotation_rolls_back_journal_claim() {
  when(refresh.refresh("old")).thenThrow(new IllegalStateException("rollback"));
  assertThatThrownBy(()->apply.apply(command)).isInstanceOf(IllegalStateException.class);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM migration_auth_request_result",Integer.class)).isZero();
 }
}
