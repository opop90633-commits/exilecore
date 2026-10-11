package tw.exilecore.admin;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 管理網頁的密碼登入（2026-10）：除了 /ec web 的一次性連結，也可以在登入畫面打密碼進來。
 * <p>
 * 密碼只在主控台設（{@code ec web password set <密碼>}；玩家打的指令會寫進 log，所以遊戲裡不能設），
 * 存的是 PBKDF2-SHA256 的雜湊加鹽（{@link #FILE}），不存密碼本身。猜錯有限制：同一個 IP 錯 {@link #PER_IP_FAILURES} 次鎖
 * {@link #LOCK}；全部加起來 {@link #GLOBAL_FAILURES} 次（換 IP 亂猜）密碼登入整個暫停 {@link #LOCK}。
 * 純 Java，不依賴 Bukkit。
 */
public final class AdminPassword {

    public static final String FILE = "admin-password.txt";
    /** 密碼最短幾個字。 */
    public static final int MIN_LENGTH = 10;
    /** 密碼最長幾個字（太長的不算，免得拿來拖慢雜湊）。 */
    public static final int MAX_LENGTH = 128;
    static final int ITERATIONS = 210_000;
    private static final String PREFIX = "pbkdf2-sha256";
    static final int PER_IP_FAILURES = 5;
    static final int GLOBAL_FAILURES = 30;
    static final Duration WINDOW = Duration.ofMinutes(10);
    static final Duration LOCK = Duration.ofMinutes(10);

    /** 登入的結果。 */
    public enum Result {
        OK, WRONG, LOCKED, NOT_SET
    }

    private final Path file;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private volatile String stored;
    private final Map<String, Deque<Instant>> failures = new HashMap<>();
    private final Deque<Instant> allFailures = new ArrayDeque<>();
    private final Map<String, Instant> lockedIps = new HashMap<>();
    private Instant lockedAll = Instant.MIN;

    /** @param file 存雜湊的檔案（plugins/ExileCore/admin-password.txt）；null = 只放記憶體（測試用） */
    public AdminPassword(final Path file, final Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    /** 讀檔（沒有檔案 = 沒設密碼）。 */
    public void load() throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            stored = null;
            return;
        }
        final String text = Files.readString(file, StandardCharsets.UTF_8).strip();
        stored = text.startsWith(PREFIX + "$") ? text : null;
    }

    /** 有沒有設密碼。 */
    public boolean enabled() {
        return stored != null;
    }

    /** 檢查新密碼能不能用；可以回傳 null。 */
    public static String problem(final String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "密碼至少 " + MIN_LENGTH + " 個字";
        }
        if (password.length() > MAX_LENGTH) {
            return "密碼最多 " + MAX_LENGTH + " 個字";
        }
        if (!password.equals(password.strip())) {
            return "密碼的開頭、結尾不能是空白";
        }
        return null;
    }

    /** 設新密碼（寫檔成功才生效）。 */
    public synchronized void set(final String password) throws IOException {
        final String error = problem(password);
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        final byte[] salt = new byte[16];
        random.nextBytes(salt);
        final String encoded = PREFIX + "$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
        write(encoded);
        stored = encoded;
        clearFailures();
    }

    /** 關掉密碼登入（刪掉檔案）。 */
    public synchronized void clear() throws IOException {
        if (file != null) {
            Files.deleteIfExists(file);
        }
        stored = null;
        clearFailures();
    }

    /** 用密碼登入（ip 用來限制猜錯的次數）。 */
    public Result attempt(final String ip, final String password) {
        final String current = stored;
        if (current == null) {
            return Result.NOT_SET;
        }
        final String key = ip == null ? "" : ip;
        synchronized (this) {
            final Instant now = clock.instant();
            if (now.isBefore(lockedAll) || now.isBefore(lockedIps.getOrDefault(key, Instant.MIN))) {
                return Result.LOCKED;
            }
        }
        // 雜湊很慢（故意的），不要佔著鎖
        final boolean ok = password != null && password.length() <= MAX_LENGTH && matches(current, password);
        synchronized (this) {
            final Instant now = clock.instant();
            if (ok) {
                failures.remove(key);
                return Result.OK;
            }
            final Instant since = now.minus(WINDOW);
            final Deque<Instant> mine = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
            mine.addLast(now);
            allFailures.addLast(now);
            while (!mine.isEmpty() && mine.peekFirst().isBefore(since)) {
                mine.pollFirst();
            }
            while (!allFailures.isEmpty() && allFailures.peekFirst().isBefore(since)) {
                allFailures.pollFirst();
            }
            if (mine.size() >= PER_IP_FAILURES) {
                lockedIps.put(key, now.plus(LOCK));
                mine.clear();
            }
            if (allFailures.size() >= GLOBAL_FAILURES) {
                lockedAll = now.plus(LOCK);
                allFailures.clear();
            }
            if (failures.size() > 1024) {
                failures.clear(); // 不讓一堆 IP 把記憶體塞滿（鎖還在 lockedIps）
            }
            lockedIps.values().removeIf(until -> !now.isBefore(until));
            return Result.WRONG;
        }
    }

    private synchronized void clearFailures() {
        failures.clear();
        allFailures.clear();
        lockedIps.clear();
        lockedAll = Instant.MIN;
    }

    static boolean matches(final String encoded, final String password) {
        final String[] parts = encoded.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            final int iterations = Integer.parseInt(parts[1]);
            final byte[] salt = Base64.getDecoder().decode(parts[2]);
            final byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(password, salt, iterations));
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(final String password, final byte[] salt, final int iterations) {
        final PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();
        }
    }

    private void write(final String encoded) throws IOException {
        if (file == null) {
            return;
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, encoded + System.lineSeparator(), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
