package tw.exilecore.web;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 統一金流 PAYUNi 的加解密（2026-10，照官方文件的 Java／PHP 範例）：
 * <ul>
 *   <li>EncryptInfo：參數組成查詢字串（跟 PHP 的 http_build_query 一樣編碼），AES-256-GCM（金鑰 Hash Key、IV Hash IV），
 *       「密文的 Base64 + ":::" + 驗證標籤的 Base64」再轉成十六進位小寫。</li>
 *   <li>HashInfo：SHA-256(Hash Key + EncryptInfo + Hash IV)，十六進位大寫。</li>
 * </ul>
 */
public final class PayUniCrypto {

    private static final int TAG_BYTES = 16;

    private final byte[] key;
    private final byte[] iv;
    private final String keyText;
    private final String ivText;

    public PayUniCrypto(final String hashKey, final String hashIv) {
        this.keyText = hashKey == null ? "" : hashKey.trim();
        this.ivText = hashIv == null ? "" : hashIv.trim();
        this.key = keyText.getBytes(StandardCharsets.UTF_8);
        this.iv = ivText.getBytes(StandardCharsets.UTF_8);
        if (key.length != 32 || iv.length != 16) {
            throw new IllegalArgumentException("Hash Key 要 32 個字、Hash IV 要 16 個字（現在是 " + key.length + "、" + iv.length + "）");
        }
    }

    /** 參數 → EncryptInfo。 */
    public String encrypt(final Map<String, String> params) {
        return encryptText(query(params));
    }

    String encryptText(final String text) {
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BYTES * 8, iv));
            final byte[] out = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
            final byte[] data = Arrays.copyOfRange(out, 0, out.length - TAG_BYTES);
            final byte[] tag = Arrays.copyOfRange(out, out.length - TAG_BYTES, out.length);
            final String joined = Base64.getEncoder().encodeToString(data) + ":::" + Base64.getEncoder().encodeToString(tag);
            return HexFormat.of().formatHex(joined.getBytes(StandardCharsets.UTF_8));
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("加密失敗", e);
        }
    }

    /** EncryptInfo → 參數；解不開（金鑰不對、被改過）丟 IllegalArgumentException。 */
    public Map<String, String> decrypt(final String encryptInfo) {
        return parse(decryptText(encryptInfo));
    }

    String decryptText(final String encryptInfo) {
        try {
            final String joined = new String(HexFormat.of().parseHex(encryptInfo.trim()), StandardCharsets.UTF_8);
            final int split = joined.indexOf(":::");
            if (split < 0) {
                throw new IllegalArgumentException("加密字串的格式不對");
            }
            final byte[] data = Base64.getDecoder().decode(joined.substring(0, split));
            final byte[] tag = Base64.getDecoder().decode(joined.substring(split + 3));
            final byte[] all = new byte[data.length + tag.length];
            System.arraycopy(data, 0, all, 0, data.length);
            System.arraycopy(tag, 0, all, data.length, tag.length);
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BYTES * 8, iv));
            return new String(cipher.doFinal(all), StandardCharsets.UTF_8);
        } catch (final GeneralSecurityException | RuntimeException e) {
            throw new IllegalArgumentException("解不開加密字串（" + e.getClass().getSimpleName() + "）", e);
        }
    }

    /** HashInfo。 */
    public String hash(final String encryptInfo) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((keyText + encryptInfo + ivText).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 收到的 HashInfo 對不對（固定時間比對）。 */
    public boolean verify(final String encryptInfo, final String hashInfo) {
        if (encryptInfo == null || hashInfo == null) {
            return false;
        }
        return MessageDigest.isEqual(hash(encryptInfo).getBytes(StandardCharsets.US_ASCII),
                hashInfo.trim().toUpperCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    /** 跟 PHP 的 http_build_query 一樣：空白變 +，其他照 RFC 1738 編碼。 */
    static String query(final Map<String, String> params) {
        final StringBuilder out = new StringBuilder();
        for (final Map.Entry<String, String> entry : params.entrySet()) {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(encode(entry.getKey())).append('=').append(encode(entry.getValue() == null ? "" : entry.getValue()));
        }
        return out.toString();
    }

    private static String encode(final String text) {
        // Java 不編碼 *，PHP 會；PHP 的 ~ 也是 %7E（Java 一樣）
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("*", "%2A");
    }

    /** 查詢字串（或表單內容）→ 參數（解碼過；同名的留第一個）。 */
    public static Map<String, String> parse(final String query) {
        final Map<String, String> out = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return out;
        }
        for (final String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            final int eq = pair.indexOf('=');
            final String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            out.putIfAbsent(name, eq < 0 ? "" : decode(pair.substring(eq + 1)));
        }
        return out;
    }

    private static String decode(final String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException e) {
            return text;
        }
    }
}
