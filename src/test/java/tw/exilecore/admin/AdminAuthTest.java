package tw.exilecore.admin;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 管理網頁的登入：一次性連結（10 分鐘、只能用一次）換成工作階段（12 小時），改東西要帶防偽造代碼。 */
class AdminAuthTest {

    /** 可以撥的時鐘。 */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-05T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(final Duration duration) {
            now = now.plus(duration);
        }
    }

    static final UUID STEVE = UUID.randomUUID();

    @Test
    void linkWorksOnceAndBecomesASession() {
        final TestClock clock = new TestClock();
        final AdminAuth auth = new AdminAuth(clock);
        final String token = auth.issue("Steve", STEVE);
        assertTrue(token.length() >= 40, "32 個隨機位元組");
        final AdminAuth.Session session = auth.redeem(token);
        assertNotNull(session);
        assertEquals("Steve", session.admin());
        assertEquals(STEVE, session.adminId());
        assertNull(auth.redeem(token), "連結只能用一次");
        assertEquals(session, auth.session(session.id()));
        assertNotEquals(session.id(), session.csrf());
        assertNotEquals(token, session.id(), "Cookie 的代碼不是連結的代碼");
    }

    @Test
    void linksExpireAfterTenMinutes() {
        final TestClock clock = new TestClock();
        final AdminAuth auth = new AdminAuth(clock);
        final String token = auth.issue("Steve", STEVE);
        clock.advance(AdminAuth.LINK_LIFETIME);
        assertNull(auth.redeem(token), "剛好 10 分鐘：過期");
        final String fresh = auth.issue("Steve", STEVE);
        clock.advance(AdminAuth.LINK_LIFETIME.minusSeconds(1));
        assertNotNull(auth.redeem(fresh));
    }

    @Test
    void sessionsExpireAndCanBeRevoked() {
        final TestClock clock = new TestClock();
        final AdminAuth auth = new AdminAuth(clock);
        final AdminAuth.Session session = auth.redeem(auth.issue("Steve", STEVE));
        clock.advance(AdminAuth.SESSION_LIFETIME.minusMinutes(1));
        assertNotNull(auth.session(session.id()));
        clock.advance(Duration.ofMinutes(1));
        assertNull(auth.session(session.id()), "12 小時後要重新登入");

        final AdminAuth.Session a = auth.redeem(auth.issue("Steve", STEVE));
        final AdminAuth.Session b = auth.redeem(auth.issue("主控台", null));
        final String pending = auth.issue("Alex", UUID.randomUUID());
        auth.logout(a.id());
        assertNull(auth.session(a.id()));
        assertNotNull(auth.session(b.id()));
        assertEquals(1, auth.revokeAll(), "/ec web logout：全部登出");
        assertNull(auth.session(b.id()));
        assertNull(auth.redeem(pending), "還沒用的連結也作廢");
    }

    @Test
    void revokeOneAdminEverywhere() {
        final AdminAuth auth = new AdminAuth(new TestClock());
        final UUID alex = UUID.randomUUID();
        final AdminAuth.Session phone = auth.redeem(auth.issue("Steve", STEVE));
        final AdminAuth.Session laptop = auth.redeem(auth.issue("Steve", STEVE));
        final String pending = auth.issue("Steve", STEVE);
        final AdminAuth.Session other = auth.redeem(auth.issue("Alex", alex));
        assertEquals(2, auth.revokeAdmin(STEVE), "沒有管理權限了：這個人每個瀏覽器都登出");
        assertNull(auth.session(phone.id()));
        assertNull(auth.session(laptop.id()));
        assertNull(auth.redeem(pending), "還沒用的連結也作廢");
        assertNotNull(auth.session(other.id()), "別人不受影響");
        assertEquals(0, auth.revokeAdmin(null), "主控台沒有 UUID");
    }

    @Test
    void newLinkReplacesTheOldOneForTheSameAdmin() {
        final AdminAuth auth = new AdminAuth(new TestClock());
        final String first = auth.issue("Steve", STEVE);
        final String other = auth.issue("Alex", UUID.randomUUID());
        final String second = auth.issue("Steve", STEVE);
        assertNull(auth.redeem(first), "同一個人只有最新的連結有效");
        assertNotNull(auth.redeem(second));
        assertNotNull(auth.redeem(other), "別人的連結不受影響");
    }

    @Test
    void csrfMustMatchExactly() {
        final AdminAuth auth = new AdminAuth(new TestClock());
        final AdminAuth.Session session = auth.redeem(auth.issue("Steve", STEVE));
        assertTrue(AdminAuth.csrfMatches(session, session.csrf()));
        assertFalse(AdminAuth.csrfMatches(session, null));
        assertFalse(AdminAuth.csrfMatches(session, ""));
        assertFalse(AdminAuth.csrfMatches(session, session.id()), "Cookie 的代碼不能拿來當防偽造代碼");
        assertFalse(AdminAuth.csrfMatches(null, session.csrf()));
    }

    @Test
    void junkTokensAreIgnored() {
        final AdminAuth auth = new AdminAuth(new TestClock());
        auth.issue("Steve", STEVE);
        assertNull(auth.redeem(null));
        assertNull(auth.redeem(""));
        assertNull(auth.redeem("short"));
        assertNull(auth.redeem("x".repeat(500)));
        assertNull(auth.redeem("../../etc/passwd-aaaaaaaaaaaaaaaaaaaa"));
        assertNull(auth.session("not a token at all !!!"));
        assertNull(auth.session(null));
    }

    @Test
    void tooManySessionsDropTheOldest() {
        final TestClock clock = new TestClock();
        final AdminAuth auth = new AdminAuth(clock);
        final AdminAuth.Session first = auth.redeem(auth.issue("a0", null));
        for (int i = 1; i < AdminAuth.MAX_SESSIONS; i++) {
            clock.advance(Duration.ofSeconds(1));
            auth.redeem(auth.issue("a" + i, null));
        }
        assertEquals(AdminAuth.MAX_SESSIONS, auth.activeSessions());
        clock.advance(Duration.ofSeconds(1));
        final AdminAuth.Session extra = auth.redeem(auth.issue("extra", null));
        assertEquals(AdminAuth.MAX_SESSIONS, auth.activeSessions());
        assertNull(auth.session(first.id()), "最早到期的先踢掉");
        assertNotNull(auth.session(extra.id()));
    }

    @Test
    void tokensAreUnique() {
        final AdminAuth auth = new AdminAuth(new TestClock());
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            assertTrue(seen.add(auth.issue("a" + i, null)));
        }
    }
}
