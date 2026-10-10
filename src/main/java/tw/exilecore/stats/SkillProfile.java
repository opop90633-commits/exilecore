package tw.exilecore.stats;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次攻擊或法術的描述：它帶哪些標籤、傷害效益多少、基礎傷害從哪裡來。
 * 沒綁技能時是「預設攻擊」；技能（階段 3）由 skill 模組依技能等級產生。
 *
 * @param id             技能代號（除錯用）
 * @param tags           標籤：attack／spell、melee／projectile、武器種類 …
 * @param effectiveness  傷害效益（1.0 = 100%）：攻擊的武器傷害與附加傷害都乘這個；法術只乘附加傷害
 * @param spellDamage    法術的基礎傷害下限（攻擊為 null）
 * @param spellDamageMax 法術的基礎傷害上限（null = 跟下限一樣）
 * @param conversion     物理傷害轉換成其他類型的比例（例如焚羽箭 {火焰: 0.5}），總和最多 1
 * @param local          只對這個技能有效的修飾器（輔助寶石），結算時疊在攻擊方的屬性表上
 */
public record SkillProfile(String id, Set<String> tags, double effectiveness, DamagePacket spellDamage,
                           DamagePacket spellDamageMax, Map<DamageType, Double> conversion, List<Modifier> local) {

    public SkillProfile {
        tags = Set.copyOf(tags);
        conversion = conversion == null || conversion.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(conversion));
        local = local == null ? List.of() : List.copyOf(local);
    }

    /** 沒有傷害範圍與轉換的簡單版本。 */
    public SkillProfile(final String id, final Set<String> tags, final double effectiveness, final DamagePacket spellDamage) {
        this(id, tags, effectiveness, spellDamage, null, Map.of(), List.of());
    }

    /** 沒有輔助寶石的版本。 */
    public SkillProfile(final String id, final Set<String> tags, final double effectiveness, final DamagePacket spellDamage,
                        final DamagePacket spellDamageMax, final Map<DamageType, Double> conversion) {
        this(id, tags, effectiveness, spellDamage, spellDamageMax, conversion, List.of());
    }

    /** 攻擊方的屬性表疊上這個技能的輔助寶石（沒有輔助就是原本那張）。 */
    public StatSheet effective(final StatSheet attacker) {
        return local.isEmpty() ? attacker : attacker.overlay(local);
    }

    /** 同一個技能再多幾條只對這一下有效的修飾器（餘震、地面效果的每一波、濺射：傷害打折或加成）。 */
    public SkillProfile withLocal(final List<Modifier> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        final List<Modifier> merged = new java.util.ArrayList<>(local);
        merged.addAll(extra);
        return new SkillProfile(id, tags, effectiveness, spellDamage, spellDamageMax, conversion, merged);
    }

    /** 這一下的傷害乘 factor（0.3 = 只有三成；1.5 = 更多 50%）：加一條「更多」修飾器。 */
    public SkillProfile scaled(final double factor, final String source) {
        if (Math.abs(factor - 1.0) < 1e-9) {
            return this;
        }
        return withLocal(List.of(Modifier.more(StatKeys.DAMAGE, factor - 1.0, source)));
    }

    /** 換個代號、多幾個標籤（投射物停下來留下的地面效果不再是投射物）。 */
    public SkillProfile derive(final String newId, final Set<String> addTags, final Set<String> removeTags) {
        final Set<String> newTags = new java.util.HashSet<>(tags);
        newTags.addAll(addTags);
        newTags.removeAll(removeTags);
        return new SkillProfile(newId, newTags, effectiveness, spellDamage, spellDamageMax, conversion, local);
    }

    public boolean isAttack() {
        return tags.contains(Tags.ATTACK);
    }

    public boolean isSpell() {
        return tags.contains(Tags.SPELL);
    }

    /** 法術基礎傷害的上限。 */
    public DamagePacket spellMax() {
        return spellDamageMax == null ? spellDamage : spellDamageMax;
    }

    /**
     * 預設攻擊：用武器的傷害，標籤 = attack + 武器種類標籤，再加上 melee（近戰武器、徒手）
     * 或 projectile（遠程武器：弓、法杖、長杖）。
     */
    public static SkillProfile defaultAttack(final Set<String> weaponTags) {
        final Set<String> tags = new HashSet<>(weaponTags);
        tags.add(Tags.ATTACK);
        tags.add(weaponTags.contains(Tags.RANGED) ? Tags.PROJECTILE : Tags.MELEE);
        return new SkillProfile("default_attack", tags, 1.0, null);
    }

    /** 怪物的普通攻擊。 */
    public static SkillProfile monsterAttack() {
        return new SkillProfile("monster_attack", Set.of(Tags.ATTACK, Tags.MELEE), 1.0, null);
    }

    /** 傷害類型的標籤：算某一種傷害時，技能自己帶的這些標籤要拿掉（焚羽箭有 fire，它的物理部分不能吃火焰修飾器）。 */
    private static final Set<String> DAMAGE_TYPE_TAGS = Set.of(Tags.PHYSICAL, Tags.FIRE, Tags.COLD, Tags.LIGHTNING,
            Tags.CHAOS, Tags.ELEMENTAL);

    /** 算某一種傷害時的情境：技能的標籤（去掉傷害類型）＋這個類型的標籤。 */
    public Set<String> contextFor(final DamageType type) {
        final Set<String> context = new HashSet<>(tags);
        context.removeAll(DAMAGE_TYPE_TAGS);
        context.addAll(type.tags());
        return context;
    }
}
