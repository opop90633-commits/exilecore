package tw.exilecore.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 管理網頁的密碼登入（{@link AdminPassword}，2026-10）。 */
class AdminPasswordTest {

    /** 可以撥的時鐘。 */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-11T00:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void storesOnlyASaltedHashAndChecksIt(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve(AdminPassword.FILE);
        final AdminPassword password = new AdminPassword(file, new TestClock());
        assertFalse(password.enabled());
        assertEquals(AdminPassword.Result.NOT_SET, password.attempt("1.1.1.1", "whatever-long"));
        password.set("correct horse battery");
        assertTrue(password.enabled());
        final String saved = Files.readString(file);
        assertFalse(saved.contains("correct horse"), "檔案裡不能有密碼本身");
        assertTrue(saved.startsWith("pbkdf2-sha256$"));
        assertEquals(AdminPassword.Result.OK, password.attempt("1.1.1.1", "correct horse battery"));
        assertEquals(AdminPassword.Result.WRONG, password.attempt("1.1.1.1", "correct horse batterY"));

        final AdminPassword reloaded = new AdminPassword(file, new TestClock());
        reloaded.load();
        assertEquals(AdminPassword.Result.OK, reloaded.attempt("2.2.2.2", "correct horse battery"), "重開之後讀檔照樣能登入");
        reloaded.clear();
        assertFalse(reloaded.enabled());
        assertFalse(Files.exists(file));
    }

    @Test
    void rejectsWeakPasswords() {
        assertNotNull(AdminPassword.problem("short"));
        assertNotNull(AdminPassword.problem(" leading space here"));
        assertNotNull(AdminPassword.problem("x".repeat(AdminPassword.MAX_LENGTH + 1)));
        assertNull(AdminPassword.problem("long enough pw"));
        assertThrows(IllegalArgumentException.class, () -> new AdminPassword(null, new TestClock()).set("short"));
    }

    @Test
    void locksAnIpAfterTooManyWrongGuesses() throws Exception {
        final TestClock clock = new TestClock();
        final AdminPassword password = new AdminPassword(null, clock);
        password.set("the real password");
        for (int i = 0; i < AdminPassword.PER_IP_FAILURES; i++) {
            assertEquals(AdminPassword.Result.WRONG, password.attempt("6.6.6.6", "guess " + i + " xxxx"));
        }
        assertEquals(AdminPassword.Result.LOCKED, password.attempt("6.6.6.6", "the real password"), "鎖住時對的也不給進");
        assertEquals(AdminPassword.Result.OK, password.attempt("7.7.7.7", "the real password"), "別的 IP 不受影響");
        clock.now = clock.now.plus(AdminPassword.LOCK).plus(Duration.ofSeconds(1));
        assertEquals(AdminPassword.Result.OK, password.attempt("6.6.6.6", "the real password"), "時間到就解鎖");
    }

    @Test
    void manyIpsGuessingPausesPasswordLoginForEveryone() throws Exception {
        final TestClock clock = new TestClock();
        final AdminPassword password = new AdminPassword(null, clock);
        password.set("the real password");
        for (int i = 0; i < AdminPassword.GLOBAL_FAILURES; i++) {
            password.attempt("10.0.0." + i, "wrong guess xx");
        }
        assertEquals(AdminPassword.Result.LOCKED, password.attempt("8.8.8.8", "the real password"));
        password.set("a brand new password");
        assertEquals(AdminPassword.Result.OK, password.attempt("8.8.8.8", "a brand new password"), "換密碼時清掉鎖");
    }
}
