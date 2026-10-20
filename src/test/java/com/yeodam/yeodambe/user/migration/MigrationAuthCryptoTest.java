package com.yeodam.yeodambe.user.migration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
class MigrationAuthCryptoTest {
 @TempDir Path temp;
 @Test void ciphertext_is_randomized_authenticated_and_topic_bound() throws Exception {
  Path p=temp.resolve("key");Files.writeString(p,"ab".repeat(32));var crypto=new MigrationAuthCrypto(p.toString());
  String a=crypto.encrypt("secret-refresh", "requests"),b=crypto.encrypt("secret-refresh","requests");
  assertThat(a).isNotEqualTo(b).doesNotContain("secret-refresh");
  assertThat(crypto.decrypt(a,"requests")).isEqualTo("secret-refresh");
  assertThatThrownBy(()->crypto.decrypt(a,"results")).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->crypto.decrypt(a.substring(0,a.length()-4)+"AAAA","requests")).isInstanceOf(IllegalStateException.class);
 }
}
