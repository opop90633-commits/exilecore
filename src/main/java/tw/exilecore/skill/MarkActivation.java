package tw.exilecore.skill;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SpellParityKeys;
import tw.exilecore.stats.StatKeys;

import java.util.List;

/**
 * 2026-10-28 照 PoE2 Voltaic Mark／Freezing Mark：被標記的敵人被電擊（引雷印）／被凍結（霜縛印）時印記「觸發」：
 * 印記用掉，放印記的人獲得「x% 傷害為額外閃電／冰冷傷害」的增益（30%、10 秒，寫在技能的 modifiers：
 * {@link SpellParityKeys#MARK_ACTIVATION_GAIN}、{@link SpellParityKeys#MARK_ACTIVATION_SECONDS}）。
 * 增益還在時再標記別的目標，增益拿掉。這次之前這兩個印記是降抗性、受到的傷害增加，沒有觸發。
 */
final class MarkActivation {

    /** 增益代號的開頭（後面接印記的技能代號）。 */
    static final String BUFF_PREFIX = "mark_gain.";

    private final ExileCore plugin;
    private final SkillService service;

    MarkActivation(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /**
     * 這個印記被哪一種異常觸發：印記效果是電擊累積的（引雷印）→ 電擊；凍結累積的（霜縛印）→ 凍結；其他回傳 null。
     *（不會造成傷害的技能載入時拿掉了元素標籤，所以看效果，不看標籤。）
     */
    static AilmentType trigger(final SkillDefinition definition) {
        if (definition.mechanic() != Mechanic.MARK || definition.params().mark() == null) {
            return null;
        }
        for (final ScaledMod effect : definition.params().mark().effects()) {
            if (effect.stat().equals(SpellParityKeys.ELECTROCUTE_BUILDUP_TAKEN)) {
                return AilmentType.ELECTROCUTE;
            }
            if (effect.stat().equals(tw.exilecore.stats.SupportExtraKeys.FREEZE_BUILDUP_TAKEN)) {
                return AilmentType.FREEZE;
            }
        }
        return null;
    }

    /** 觸發後的增益給哪一種額外傷害。 */
    static DamageType gainType(final AilmentType trigger) {
        return trigger == AilmentType.ELECTROCUTE ? DamageType.LIGHTNING : DamageType.COLD;
    }

    /** 技能自己 modifiers 裡某個 FLAT 屬性的值（沒有 = 0）。 */
    static double flat(final List<Modifier> modifiers, final String stat) {
        double total = 0;
        for (final Modifier modifier : modifiers) {
            if (modifier.stat().equals(stat) && modifier.type() == tw.exilecore.stats.ModType.FLAT) {
                total += modifier.value();
            }
        }
        return total;
    }

    /** 敵人被電擊／凍結了（AilmentService）：身上會被這種異常觸發的印記用掉，放印記的人拿到增益。 */
    void ailment(final LivingEntity target, final AilmentType type) {
        if (plugin.combat() == null || plugin.combat().debuffs() == null) {
            return;
        }
        final Debuffs debuffs = plugin.combat().debuffs();
        final long now = plugin.getServer().getCurrentTick();
        for (final Debuffs.Debuff debuff : List.copyOf(debuffs.active(target.getUniqueId(), now))) {
            final SkillDefinition definition = service.config().skill(debuff.id());
            if (definition == null || trigger(definition) != type) {
                continue;
            }
            final double gain = flat(definition.modifiers(), SpellParityKeys.MARK_ACTIVATION_GAIN);
            final double seconds = flat(definition.modifiers(), SpellParityKeys.MARK_ACTIVATION_SECONDS);
            if (gain <= 0 || seconds <= 0) {
                continue;
            }
            debuffs.remove(target.getUniqueId(), debuff.id(), debuff.source(), now); // 印記用掉
            final Player owner = plugin.getServer().getPlayer(debuff.source());
            if (owner == null || !owner.isOnline()) {
                continue;
            }
            service.buff(owner, BUFF_PREFIX + definition.id(), definition.name(), (int) Math.round(seconds * 20),
                    List.of(Modifier.flat(StatKeys.damageExtra(gainType(type)), gain, definition.name())));
        }
    }

    /** 標記了新的目標：之前同一個印記觸發給的增益拿掉（PoE2：Marking another target while you have the Buff will remove the Buff）。 */
    void marked(final Player player, final SkillDefinition definition) {
        if (flat(definition.modifiers(), SpellParityKeys.MARK_ACTIVATION_GAIN) > 0) {
            service.removeBuff(player, BUFF_PREFIX + definition.id());
        }
    }
}
