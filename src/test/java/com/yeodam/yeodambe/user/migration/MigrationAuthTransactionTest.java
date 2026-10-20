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
  jdbc.execute("CREATE TABLE IF NOT EXISTS migration_auth_request_result(request_id VARCHAR(64) PRIMARY KEY,encrypted_result TEXT NOT NULL)");
  jdbc.execute("DELETE FROM migration_auth_request_result");
  jdbc.execute("CREATE TABLE IF NOT EXISTS login_sessions(refresh_token_hash VARCHAR(64),expires_at TIMESTAMP)");
  jdbc.execute("DELETE FROM login_sessions");
  jdbc.update("INSERT INTO login_sessions VALUES (?,DATEADD('DAY',1,CURRENT_TIMESTAMP))",new com.yeodam.yeodambe.user.security.TokenHasher().hash("new"));
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
 @Test void failed_rotation_rolls_back_journal_claim() {
  when(refresh.refresh("old")).thenThrow(new IllegalStateException("rollback"));
  assertThatThrownBy(()->apply.apply(command)).isInstanceOf(IllegalStateException.class);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM migration_auth_request_result",Integer.class)).isZero();
 }
}
