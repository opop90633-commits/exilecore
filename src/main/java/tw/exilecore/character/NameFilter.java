package tw.exilecore.character;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 角色名稱的過濾（2026-10）：辱罵、色情、種族歧視、仇恨的字，以及冒充管理員的名字不能取。純邏輯，不依賴 Bukkit。
 * <ul>
 *   <li>{@link #BLOCKED}：名字裡任何地方出現就不行；</li>
 *   <li>{@link #RESERVED}：名字開頭是這些就不行（冒充管理員、官方）。</li>
 * </ul>
 * 比對前先統一寫法：全形轉半形、英文轉小寫、簡體常見字轉繁體、拿掉底線，數字代字母（0→o、1→i、3→e、4→a、5→s、7→t、8→b）也還原，
 * 所以「F_U_C_K」「fvck」以外的常見變形、「Ｆｕｃｋ」、「肏你妈」都抓得到。
 * 設定檔（character.yml 的 name_filter）可以再加字（blocked、reserved），也可以放行誤擋的名字（allow：整個名字一樣就放行）。
 */
public final class NameFilter {

    /** 名字裡出現就不行的字（英文都寫小寫）。 */
    static final List<String> BLOCKED = List.of(
            // 中文辱罵、粗話
            "幹你娘", "幹您娘", "干你娘", "操你", "肏", "屄", "屌你", "雞掰", "機掰", "雞巴", "機巴", "他媽的", "她媽的", "你媽的", "你媽逼",
            "媽的", "靠北", "靠杯", "靠夭", "王八蛋", "狗娘養", "賤人", "賤貨", "婊子", "妓女", "雞婆子", "破麻", "淫", "智障",
            "腦殘", "白痴", "白癡", "低能", "弱智", "廢物", "垃圾人", "去死", "死全家", "畜生", "畜牲", "人渣", "敗類", "狗東西", "龜兒子",
            "孬種", "下賤", "賤種", "雜種", "洨", "懶叫", "卵叫", "奶子", "陰莖", "陰道", "陰毛", "性交", "做愛", "口交",
            "強姦", "強暴", "輪姦", "迷姦", "援交", "嫖", "色情", "淫蕩", "騷貨", "發騷",
            // 種族、地域、族群歧視
            "支那", "黑鬼", "黑奴", "尼哥", "黑猴", "鬼子", "倭寇", "倭奴", "小日本", "台巴子", "灣灣", "阿六仔", "426", "蝗蟲", "高麗棒子",
            "棒子國", "猶太豬", "白皮豬", "番仔", "山地人", "死原住民", "外勞仔",
            // 仇恨、極端
            "納粹", "希特勒", "屠殺", "種族滅絕", "恐怖分子", "恐怖份子", "自殺",
            // 英文
            "fuck", "fck", "shit", "bitch", "biatch", "cunt", "dick", "pussy", "penis", "vagina", "whore", "slut", "bastard",
            "asshole", "motherf", "wanker", "twat", "porn", "rapist", "nigger", "nigga", "faggot", "fagot",
            "retard", "chink", "kike", "gook", "wetback", "tranny", "nazi", "hitler", "kkk", "jihad", "terrorist",
            "suicide", "killyourself", "kys");

    /** 名字開頭是這些就不行（冒充管理員、官方、系統訊息）。 */
    static final List<String> RESERVED = List.of(
            "admin", "gm", "moderator", "owner", "staff", "official", "server", "console", "system",
            "管理員", "管理者", "管理", "官方", "客服", "系統", "伺服器", "站長", "服主");

    /** 簡體常見字 → 繁體（只收過濾字用得到的）。 */
    private static final String SIMPLIFIED = "妈干鸡机贱渣杀狗种杂恶淫强奸暴轮迷援骚发阴脑痴废垃圾全龟贝鬼纳粹杀屠灭恐怖份系统务员伺服务器丽猪弯凯";
    private static final String TRADITIONAL = "媽幹雞機賤渣殺狗種雜惡淫強姦暴輪迷援騷發陰腦癡廢垃圾全龜貝鬼納粹殺屠滅恐怖份系統務員伺服務器麗豬彎凱";

    /**
     * 2026-10-18：一份建好就不再改的字表；聊天在非同步執行緒讀、重載在主執行緒建新的一份再用 volatile 一次換上
     * （以前是主執行緒直接 clear 重建，聊天執行緒可能讀到清到一半的表或 ConcurrentModificationException）。
     *
     * @param blockedPlain 同樣的字，數字不轉成字母的寫法（426 這種數字本身就是歧視用語的）
     * @param chatWords    聊天用：字的「摺疊寫法」（小寫、簡轉繁，長度不變），在訊息裡找出來換成＊
     * @param disguised    聊天用：拆開、數字代字母的寫法要比對的字（比對用的寫法；純數字的字不在這裡）
     */
    private record Words(Set<String> blocked, Set<String> blockedPlain, Set<String> reserved, Set<String> allowed,
                         Set<String> chatWords, Set<String> disguised) {
    }

    private volatile Words words;

    public NameFilter() {
        configure(List.of(), List.of(), List.of());
    }

    /** 內建的字再加上設定檔的 blocked、reserved；allow 是整個名字一樣就放行的例外。 */
    public void configure(final Collection<String> extraBlocked, final Collection<String> extraReserved, final Collection<String> allow) {
        final Set<String> blocked = new LinkedHashSet<>();
        final Set<String> blockedPlain = new LinkedHashSet<>();
        final Set<String> reserved = new LinkedHashSet<>();
        final Set<String> allowed = new LinkedHashSet<>();
        final Set<String> chatWords = new LinkedHashSet<>();
        final Set<String> disguised = new LinkedHashSet<>();
        for (final String word : BLOCKED) {
            add(blocked, word);
            addPlain(blockedPlain, word);
            addFold(chatWords, word);
            if (!word.chars().allMatch(Character::isDigit)) {
                add(disguised, word);
            }
        }
        for (final String word : extraBlocked) {
            add(blocked, word);
            addPlain(blockedPlain, word);
            addFold(chatWords, word);
            if (!word.chars().allMatch(Character::isDigit)) {
                add(disguised, word);
            }
        }
        for (final String word : RESERVED) {
            add(reserved, word);
        }
        for (final String word : extraReserved) {
            add(reserved, word);
        }
        for (final String word : allow) {
            add(allowed, word);
        }
        // 2026-10-18：全部建好才一次換上（照原本的順序，不可變）
        words = new Words(java.util.Collections.unmodifiableSet(blocked), java.util.Collections.unmodifiableSet(blockedPlain),
                java.util.Collections.unmodifiableSet(reserved), java.util.Collections.unmodifiableSet(allowed),
                java.util.Collections.unmodifiableSet(chatWords), java.util.Collections.unmodifiableSet(disguised));
    }

    private static void addFold(final Set<String> set, final String word) {
        final String value = fold(Normalizer.normalize(word, Normalizer.Form.NFKC)).strip();
        if (!value.isEmpty()) {
            set.add(value);
        }
    }

    /** 小寫、簡轉繁，一個字換一個字（長度不變，找到的位置可以對回原文）。 */
    static String fold(final String text) {
        final String lower = text.toLowerCase(Locale.ROOT);
        if (lower.length() != text.length()) {
            return text; // 很少見：小寫後長度變了，就不轉
        }
        final StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            final char c = lower.charAt(i);
            final int index = SIMPLIFIED.indexOf(c);
            out.append(index >= 0 ? TRADITIONAL.charAt(index) : c);
        }
        return out.toString();
    }

    /**
     * 聊天訊息（2026-10）：直接寫出來的字換成＊（不分大小寫、全形半形、簡繁）。數字的字（426）前後是數字時不算（傷害 14260 不會被換掉）。
     * 回傳全形轉半形之後、換好的訊息。
     */
    public String maskChat(final String text) {
        final String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        final String folded = fold(normalized);
        final char[] out = normalized.toCharArray();
        boolean changed = false;
        for (final String word : words.chatWords()) { // 2026-10-18：讀當下那一份
            final boolean digits = word.chars().allMatch(Character::isDigit);
            int from = 0;
            while (true) {
                final int at = folded.indexOf(word, from);
                if (at < 0) {
                    break;
                }
                final int end = at + word.length();
                from = at + 1;
                if (digits && (at > 0 && Character.isDigit(folded.charAt(at - 1)) || end < folded.length() && Character.isDigit(folded.charAt(end)))) {
                    continue;
                }
                for (int i = at; i < end && i < out.length; i++) {
                    if (!Character.isWhitespace(out[i])) {
                        out[i] = '＊';
                    }
                }
                changed = true;
            }
        }
        return changed ? new String(out) : normalized;
    }

    /**
     * 聊天訊息裡有沒有「故意拆開、用數字代字母」的寫法（f_u_c_k、sh1t）：一個詞一個詞看（空白隔開的），不跨詞接起來
     * （this hit 不會變成 shit）。數字的字不看（交給 {@link #maskChat}）。
     */
    public boolean disguisedInChat(final String text) {
        final Words words = this.words; // 2026-10-18：整段用同一份字表
        for (final String token : Normalizer.normalize(text, Normalizer.Form.NFKC).split("\\s+")) {
            final String canonical = canonical(token);
            if (canonical.isEmpty() || words.allowed().contains(canonical)) {
                continue;
            }
            for (final String word : words.disguised()) {
                if (canonical.contains(word)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void addPlain(final Set<String> set, final String word) {
        final String value = plain(word);
        if (!value.isEmpty()) {
            set.add(value);
        }
    }

    private static void add(final Set<String> set, final String word) {
        final String canonical = canonical(word);
        if (!canonical.isEmpty()) {
            set.add(canonical);
        }
    }

    /** 不能用的話回傳原因（不寫是哪個字，免得變成教人怎麼繞過）；可以用回傳 null。 */
    public String problem(final String name) {
        final Words words = this.words; // 2026-10-18：整段用同一份字表
        final String canonical = canonical(name);
        if (canonical.isEmpty() || words.allowed().contains(canonical)) {
            return null;
        }
        for (final String word : words.reserved()) {
            if (canonical.startsWith(word)) {
                return "這個名稱容易被誤會成管理員或官方，換一個吧";
            }
        }
        for (final String word : words.blocked()) {
            if (canonical.contains(word)) {
                return "名稱含有不雅、歧視或敏感的字，換一個吧";
            }
        }
        // 也看「數字不轉成字母」的寫法（例如 426 這種數字本身就是歧視用語的）
        final String plain = plain(name);
        for (final String word : words.blockedPlain()) {
            if (plain.contains(word)) {
                return "名稱含有不雅、歧視或敏感的字，換一個吧";
            }
        }
        return null;
    }

    /** 比對用的寫法：全形轉半形、小寫、簡轉繁、拿掉底線與空白、數字代字母還原。 */
    static String canonical(final String text) {
        final String plain = plain(text);
        final StringBuilder out = new StringBuilder(plain.length());
        for (int i = 0; i < plain.length(); i++) {
            final char c = plain.charAt(i);
            out.append(switch (c) {
                case '0' -> 'o';
                case '1' -> 'i';
                case '3' -> 'e';
                case '4' -> 'a';
                case '5' -> 's';
                case '7' -> 't';
                case '8' -> 'b';
                case '@' -> 'a';
                case '$' -> 's';
                case 'v' -> i > 0 && plain.charAt(i - 1) == 'f' ? 'u' : 'v'; // fvck
                default -> c;
            });
        }
        return out.toString();
    }

    /** 全形轉半形、小寫、簡轉繁、拿掉底線與空白（數字不動）。 */
    static String plain(final String text) {
        if (text == null) {
            return "";
        }
        final String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        final StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            final char c = normalized.charAt(i);
            if (c == '_' || c == '-' || c == '.' || Character.isWhitespace(c)) {
                continue;
            }
            final int index = SIMPLIFIED.indexOf(c);
            out.append(index >= 0 ? TRADITIONAL.charAt(index) : c);
        }
        return out.toString();
    }

    /** 給測試：目前的字（比對用的寫法）。 */
    List<String> blockedWords() {
        return new ArrayList<>(words.blocked());
    }
}
