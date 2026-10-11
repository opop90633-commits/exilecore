package tw.exilecore.skill;

import java.util.Locale;
import java.util.Set;

/**
 * Boss 招式的提示（2026-10，終局與試煉 Boss 的特殊招式要在畫面中間與聊天室說清楚）：純邏輯，不依賴 Bukkit。
 * <p>
 * 特殊招式（要特別應對的運作方式，或資料檔寫了 {@code hint} 的）：附近的玩家畫面中間跳大字（招式名稱）＋副標題（怎麼應對），
 * 聊天室寫一行（同一招 {@link #CHAT_GAP_TICKS} 內只寫一次）。其他招式看地板的顏色（動作列不寫招式）。
 * 場地危害第一次落下時也說明一次。
 */
public final class BossAlerts {

    /** 同一招的聊天室說明最少隔多久（30 秒）：畫面中間每次都跳，聊天室不洗版。 */
    public static final long CHAT_GAP_TICKS = 30 * 20;

    /** 一定算特殊招式的運作方式（落下的紅圈、地面效果、扇形、齊射要寫了 hint 才算）。 */
    private static final Set<BossSkill.Kind> SPECIAL = Set.of(BossSkill.Kind.NOVA, BossSkill.Kind.RING, BossSkill.Kind.ORBS,
            BossSkill.Kind.BEAM, BossSkill.Kind.PULL, BossSkill.Kind.SANCTUARY, BossSkill.Kind.CURSE, BossSkill.Kind.SUMMON,
            BossSkill.Kind.CHARGE, BossSkill.Kind.BLINK, BossSkill.Kind.MARK, BossSkill.Kind.ECHOES, BossSkill.Kind.ARENA);

    private BossAlerts() {
    }

    /** 放這招時要不要跳大字、寫聊天室。 */
    public static boolean special(final BossSkill skill) {
        return SPECIAL.contains(skill.kind()) || skill.hint() != null;
    }

    /** 會要命的（全場大招、吸人）：大字用紅色，其他用橘色。 */
    public static boolean deadly(final BossSkill skill) {
        return skill.kind() == BossSkill.Kind.SANCTUARY || skill.kind() == BossSkill.Kind.PULL;
    }

    /** 怎麼應對：資料檔的 hint；沒寫照運作方式給一句。純文字（還沒跳脫 MiniMessage）。 */
    public static String hint(final BossSkill skill) {
        if (skill.hint() != null && !skill.hint().isBlank()) {
            return skill.hint().trim();
        }
        return switch (skill.kind()) {
            case NOVA -> String.format(Locale.ROOT, "從 Boss 身邊爆開：馬上往外跑出 %d 格", Math.round(skill.radius()));
            case RING -> skill.gaps() > 0 ? "擴散環往外推：站進綠色的缺口"
                    : String.format(Locale.ROOT, "擴散環往外推：跑到 %d 格外", Math.round(skill.reach()));
            case ORBS -> "光球會追著人跑：一直移動，別被碰到";
            case BEAM -> "光束會轉著掃：繞著 Boss 跑，跟光束反方向走";
            case PULL -> "把人往 Boss 吸、最後中間爆炸：一直往外跑";
            case SANCTUARY -> "全場大招：躲進綠色的安全區";
            case CURSE -> skill.duration() > 0
                    ? String.format(Locale.ROOT, "詛咒：%d 秒內受到的傷害增加 %d%%", Math.round(skill.duration()), Math.round(skill.taken()))
                    : String.format(Locale.ROOT, "詛咒：受到的傷害增加 %d%%", Math.round(skill.taken()));
            case SUMMON -> "叫出小怪：先清掉小怪";
            case CHARGE -> "朝你衝過來：往旁邊閃開";
            case BLINK -> skill.dealsDamage() ? "撲到你身邊、落地會炸：馬上離開腳下的紅圈" : "瞬移到你身邊：注意下一招";
            case SLAM -> "紅圈會落下：離開紅圈";
            case ZONE -> "地上留下傷害區：別站在裡面";
            case CONE -> "前方扇形：繞到 Boss 側面或後面";
            case VOLLEY -> "一排魔法彈：往旁邊閃";
            case MARK -> String.format(Locale.ROOT, "點名標記 %d 秒後爆炸：被點名的離開隊友", Math.round(skill.duration()));
            case ECHOES -> "殘影一個接一個朝人射出長槍：看紅線往旁邊走";
            case ARENA -> "場地在收縮：待在圈內、靠近 Boss";
        };
    }

    /** 場地危害第一次落下時的說明（純文字）：資料檔的 hint 或照運作方式，再加上大約多久一次。 */
    public static String hazardHint(final BossSkill skill, final double everySeconds) {
        final String base = skill.hint() != null && !skill.hint().isBlank() ? skill.hint().trim() : switch (skill.kind()) {
            case ZONE -> "地上會一直冒出傷害區：別站在裡面";
            case ORBS -> "光球會一直追著人：保持移動";
            case MARK -> "會一直點名玩家：被點名的離開隊友";
            default -> "紅圈會一直落在玩家附近：看到紅圈就離開";
        };
        return everySeconds > 0 ? String.format(Locale.ROOT, "%s（大約每 %d 秒）", base, Math.round(everySeconds)) : base;
    }

    /** 畫面中間的大字留多久（tick）：前搖再多一點，至少 1.2 秒、最多 3 秒。 */
    public static int titleTicks(final BossSkill skill) {
        return (int) Math.max(24, Math.min(60, Math.round(skill.telegraph() * 20) + 12));
    }
}
