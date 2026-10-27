package tw.exilecore.skill;

import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.GrantedSkillKeys;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.HashSet;
import java.util.Set;

/**
 * 2026-10-28 照 PoE2 的「像造成 x 到 y 點傷害一樣施加異常」（as though dealing）：寒心的冰緩、屍焰爆核心的點燃。
 * 基礎傷害寫在技能的 level_modifiers（{@link GrantedSkillKeys#AS_THOUGH_MIN}／{@link GrantedSkillKeys#AS_THOUGH_MAX}，逐級），
 * 照這個技能的加成（增加、更多，帶這種傷害的標籤）算成平均傷害，只拿來算異常，不打人。
 */
final class AsThough {

    private AsThough() {
    }

    /** 技能屬性表上寫的「像造成」基礎傷害 [最小, 最大]（沒寫是 [0, 0]）。 */
    static double[] range(final StatSheet effective, final Set<String> tags) {
        return new double[] {Math.max(0, effective.flat(GrantedSkillKeys.AS_THOUGH_MIN, tags)),
                Math.max(0, effective.flat(GrantedSkillKeys.AS_THOUGH_MAX, tags))};
    }

    /**
     * 拿 base 這個技能的標籤與輔助，換成只帶 type 一種傷害、基礎 [min, max] 的版本（帶法術與這種傷害的標籤，吃對應的加成）。純函式（測試用）。
     */
    static SkillProfile profile(final SkillProfile base, final DamageType type, final double min, final double max) {
        final Set<String> tags = new HashSet<>(base.tags());
        tags.add(type.tag());
        tags.add(Tags.SPELL); // 寒心、屍焰爆都是法術（原作的 Spell 標籤；光環說明拿掉了傷害標籤，這裡補回來）
        final DamagePacket low = new DamagePacket().set(type, min);
        final DamagePacket high = new DamagePacket().set(type, Math.max(min, max));
        return new SkillProfile(base.id(), tags, base.effectiveness(), low, high, java.util.Map.of(), base.local());
    }

    /** 照 ownerSheet 與 profile 的加成算出「像造成」的平均傷害（不擲暴擊、減免前）。 */
    static double amount(final StatSheet ownerSheet, final SkillProfile profile, final DamageType type) {
        return DamageCalculator.expectedOutgoing(ownerSheet, profile).get(type);
    }
}
