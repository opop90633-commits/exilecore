package tw.exilecore.skill;

import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.List;

/**
 * 2026-10-30 照 PoE2 的植物過度生長（poe2db「植物技能與過度生長」；遊戲裡叫「繁茂」，天賦樹的寫法）：
 * 植物技能造出來的植物可以繁茂，照各技能說明變強；植物技能自己通常不會讓它繁茂，要靠別的：
 * <ul>
 *   <li>積雷雲（Thunderstorm）：範圍內你的植物會繁茂（{@link SkillZones#inThunderstorm}）。</li>
 *   <li>天賦「無限生長」：植物有 20% 機率立即繁茂（{@link #CHANCE}，造出來時擲一次）。</li>
 * </ul>
 * 繁茂的效果（RePoE 各技能的 *_when_wet）：縛根的裂隙消失慢 25%、多纏 3 條藤蔓（{@link EntangleRoots}）；
 * 荊棘矢的荊棘 50% 更多傷害、50% 更多範圍；荊棘叢生 50% 更多傷害；抽荊的藤蔓 50% 更多傷害、50% 更大。
 * 天賦「回歸自然」：繁茂的植物技能增加 50% 破甲（{@link #ARMOUR_BREAK}）。輔助「催綻」：被輔助的技能造出來的植物繁茂時觸發綻花爆（{@link #TRIGGER}）。
 * 原作還有：刺棘身的荊棘地（我們的刺棘身是反傷增益，沒有荊棘地）、伊娜魔偶、長杖的自然統治者（我們沒有這些物品）——沒做。
 */
final class Overgrowth {

    /** FLAT（%，玩家身上，plant 標籤）：植物造出來時立即繁茂的機率（天賦「無限生長」20）。 */
    static final String CHANCE = "plant.overgrow_chance";
    /** INCREASED（玩家身上，plant 標籤）：繁茂的植物技能的破甲增加多少（天賦「回歸自然」0.5）。 */
    static final String ARMOUR_BREAK = "plant.overgrown_armour_break";
    /** FLAT（輔助）：被輔助的技能造出來的植物繁茂時觸發綻花爆（催綻）。 */
    static final String TRIGGER = "plant.overgrow_trigger";
    static final String TRIGGER_SKILL = "proc_accelerated_growth";
    /** 讓範圍內植物繁茂的技能。 */
    static final String THUNDERSTORM = "gathered_thundercloud";
    /** 縛根：繁茂時剩下的時間 ÷ 0.75（消失慢 25%）、每個敵人多纏 3 條。 */
    static final double ENTANGLE_SLOWER = 0.25;
    static final int ENTANGLE_EXTRA_VINES = 3;

    /** 繁茂時的更多傷害、更多範圍（小數）。 */
    record Effect(double damageMore, double areaMore) {
    }

    private Overgrowth() {
    }

    /** 這個技能（技能描述代號）造出來的植物繁茂時怎麼變強；不會繁茂的回傳 null。 */
    static Effect effect(final String profileId) {
        if (profileId == null) {
            return null;
        }
        final String id = profileId.contains(".") ? profileId.substring(0, profileId.indexOf('.')) : profileId;
        return switch (id) {
            case "thorn_shaft", "lashing_thorns" -> new Effect(0.5, 0.5); // Vine Arrow、Thrashing Vines
            case "proc_bramble" -> new Effect(0.5, 0); // Brambles
            case EntangleRoots.ID -> new Effect(0, 0); // 效果在 EntangleRoots
            default -> null;
        };
    }

    /** 繁茂的植物：傷害更多、回歸自然的破甲。 */
    static SkillProfile apply(final SkillProfile profile, final Effect effect, final StatSheet owner) {
        SkillProfile out = profile;
        if (effect != null && effect.damageMore() > 0) {
            out = out.withLocal(List.of(Modifier.more(StatKeys.DAMAGE, effect.damageMore(), "繁茂")));
        }
        final double armourBreak = owner == null ? 0 : owner.increased(ARMOUR_BREAK, profile.tags());
        if (armourBreak > 0) {
            out = out.withLocal(List.of(Modifier.increased(StatKeys.ARMOUR_BREAK, armourBreak, "回歸自然")));
        }
        return out;
    }

    /** 造出來時立即繁茂的機率（0～1）。 */
    static double chance(final StatSheet owner, final SkillProfile profile) {
        return owner == null ? 0 : Math.max(0, Math.min(100, owner.flat(CHANCE, profile.tags()))) / 100.0;
    }

    /** 半徑乘多少（範圍更多 x → 半徑 ×√(1＋x)）。 */
    static double radiusScale(final Effect effect) {
        return effect == null ? 1 : Math.sqrt(1 + effect.areaMore());
    }
}
