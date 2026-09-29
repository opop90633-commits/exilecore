package tw.exilecore.stats;

import java.util.HashSet;
import java.util.Set;

/**
 * 一次攻擊或法術的描述：它帶哪些標籤、傷害效益多少、基礎傷害從哪裡來。
 * 階段 1 只有「預設攻擊」；階段 3 的技能寶石會產生各種 SkillProfile。
 *
 * @param id            技能代號（除錯用）
 * @param tags          標籤：attack／spell、melee／projectile、武器種類 …
 * @param effectiveness 傷害效益（1.0 = 100%）：武器傷害與附加傷害都乘這個
 * @param spellDamage   法術的基礎傷害（攻擊為 null）
 */
public record SkillProfile(String id, Set<String> tags, double effectiveness, DamagePacket spellDamage) {

    public SkillProfile {
        tags = Set.copyOf(tags);
    }

    public boolean isAttack() {
        return tags.contains(Tags.ATTACK);
    }

    public boolean isSpell() {
        return tags.contains(Tags.SPELL);
    }

    /** 預設攻擊：用武器的傷害，標籤 = attack + melee + 武器種類標籤。 */
    public static SkillProfile defaultAttack(final Set<String> weaponTags) {
        final Set<String> tags = new HashSet<>(weaponTags);
        tags.add(Tags.ATTACK);
        tags.add(Tags.MELEE);
        return new SkillProfile("default_attack", tags, 1.0, null);
    }

    /** 怪物的普通攻擊。 */
    public static SkillProfile monsterAttack() {
        return new SkillProfile("monster_attack", Set.of(Tags.ATTACK, Tags.MELEE), 1.0, null);
    }

    /** 把傷害類型的標籤加進情境。 */
    public Set<String> contextFor(final DamageType type) {
        final Set<String> context = new HashSet<>(tags);
        context.addAll(type.tags());
        return context;
    }
}
