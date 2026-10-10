package tw.exilecore.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理網頁的登入：管理員在遊戲裡打 /ec web 拿一個一次性的連結（{@link #LINK_LIFETIME} 內有效、用過就失效），
 * 打開連結換成一個工作階段（Cookie，{@link #SESSION_LIFETIME}）。每個工作階段另外有一個防偽造的代碼，
 * 改東西的請求（POST）要在標頭帶上它，別的網站就算騙管理員的瀏覽器送請求也帶不出來。
 * <p>
 * 連結與工作階段的代碼都是 32 個隨機位元組；記在記憶體裡的只有雜湊（重開插件全部失效）。純 Java，不依賴 Bukkit。
 */
public final class AdminAuth {

    public static final Duration LINK_LIFETIME = Duration.ofMinutes(10);
    public static final Duration SESSION_LIFETIME = Duration.ofHours(12);
    /** 同時有效的工作階段上限（太多時最早到期的先踢掉）。 */
    static final int MAX_SESSIONS = 16;
    /** 還沒用的連結上限。 */
    static final int MAX_LINKS = 32;

    /**
     * 一個登入中的管理員。
     *
     * @param id      Cookie 裡的代碼
     * @param csrf    改東西時要帶的防偽造代碼
     * @param admin   誰（玩家名稱；主控台是「主控台」）
     * @param adminId 玩家的 UUID（主控台是 null）
     */
    public record Session(String id, String csrf, String admin, UUID adminId, Instant expires) {
    }

    private record Link(String admin, UUID adminId, Instant expires) {
    }

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    /** 連結代碼的雜湊 → 連結。 */
    private final Map<String, Link> links = new ConcurrentHashMap<>();
    /** 工作階段代碼的雜湊 → 工作階段。 */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public AdminAuth(final Clock clock) {
        this.clock = clock;
    }

    /** 發一個登入連結的代碼；同一個管理員之前還沒用的連結作廢（只有最新的那個有效）。 */
    public synchronized String issue(final String admin, final UUID adminId) {
        prune();
        links.values().removeIf(link -> link.admin().equals(admin) && java.util.Objects.equals(link.adminId(), adminId));
        while (links.size() >= MAX_LINKS) {
            links.entrySet().stream().min(Comparator.comparing(entry -> entry.getValue().expires()))
                    .ifPresent(oldest -> links.remove(oldest.getKey()));
        }
        final String token = token();
        links.put(hash(token), new Link(admin, adminId, clock.instant().plus(LINK_LIFETIME)));
        return token;
    }

    /** 用連結登入：成功回傳新的工作階段（連結同時失效）；過期、用過、亂打的回傳 null。 */
    public synchronized Session redeem(final String token) {
        if (!plausible(token)) {
            return null;
        }
        prune();
        final Link link = links.remove(hash(token));
        if (link == null || !clock.instant().isBefore(link.expires())) {
            return null;
        }
        while (sessions.size() >= MAX_SESSIONS) {
            sessions.entrySet().stream().min(Comparator.comparing(entry -> entry.getValue().expires()))
                    .ifPresent(oldest -> sessions.remove(oldest.getKey()));
        }
        final Session session = new Session(token(), token(), link.admin(), link.adminId(), clock.instant().plus(SESSION_LIFETIME));
        sessions.put(hash(session.id()), session);
        return session;
    }

    /** Cookie 裡的代碼對到的工作階段；沒有或過期回傳 null。 */
    public Session session(final String id) {
        if (!plausible(id)) {
            return null;
        }
        final String key = hash(id);
        final Session session = sessions.get(key);
        if (session == null) {
            return null;
        }
        if (!clock.instant().isBefore(session.expires())) {
            sessions.remove(key);
            return null;
        }
        return session;
    }

    /** 改東西的請求帶的防偽造代碼對不對（固定時間比較）。 */
    public static boolean csrfMatches(final Session session, final String header) {
        if (session == null || header == null) {
            return false;
        }
        return MessageDigest.isEqual(session.csrf().getBytes(StandardCharsets.UTF_8), header.trim().getBytes(StandardCharsets.UTF_8));
    }

    /** 登出這個工作階段。 */
    public void logout(final String id) {
        if (plausible(id)) {
            sessions.remove(hash(id));
        }
    }

    /** 這個管理員的工作階段全部登出、還沒用的連結作廢（沒有管理權限了）；回傳登出了幾個。 */
    public synchronized int revokeAdmin(final UUID adminId) {
        if (adminId == null) {
            return 0;
        }
        links.values().removeIf(link -> adminId.equals(link.adminId()));
        final int before = sessions.size();
        sessions.values().removeIf(session -> adminId.equals(session.adminId()));
        return before - sessions.size();
    }

    /** 全部登出、還沒用的連結全部作廢；回傳登出了幾個工作階段。 */
    public synchronized int revokeAll() {
        prune();
        final int count = sessions.size();
        sessions.clear();
        links.clear();
        return count;
    }

    /** 現在有幾個登入中的工作階段。 */
    public int activeSessions() {
        prune();
        return sessions.size();
    }

    /** 丟掉過期的連結與工作階段。 */
    public void prune() {
        final Instant now = clock.instant();
        links.values().removeIf(link -> !now.isBefore(link.expires()));
        sessions.values().removeIf(session -> !now.isBefore(session.expires()));
    }

    private String token() {
        final byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 長度與字元合理（亂打的、超長的直接不查）。 */
    private static boolean plausible(final String token) {
        return token != null && token.length() >= 20 && token.length() <= 100 && token.matches("[A-Za-z0-9_-]+");
    }

    private static String hash(final String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
