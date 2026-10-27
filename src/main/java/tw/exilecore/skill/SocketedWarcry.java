package tw.exilecore.skill;

import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.Tags;
import tw.exilecore.stats.WarcryKeys;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-27 照 PoE2 Ferocious Roar（poe2db.tw/us/Ferocious_Roar；RePoE SupportFerociousRoarPlayer）：
 * 裂甲熊咆的第 1 孔可以放一顆人形戰吼的技能寶石（allowed Warcry；不能是 Meta、Shapeshift、InbuiltTrigger），
 * 放了之後用裂甲熊咆就改成觸發那個戰吼、不變身也沒有熊咆自己的效果（「Triggers Socketed Warcry instead of Ferocious Roar's effects」）：
 * <ul>
 *   <li>插槽戰吼範圍增加 50%、傷害增加 30%～87%（隨熊咆等級，40 級 132%）；被它強化的攻擊增加同樣多的傷害。</li>
 *   <li>熊咆的其他孔的輔助套在插槽戰吼上（不合的略過）；插槽戰吼照樣吃自己的品質。</li>
 *   <li>花費：熊咆自己的魔力，加上插槽戰吼的魔力 ×消耗倍率（熊咆 1 級 100%、2 級起 200%）；榮耀這類要求照樣要。</li>
 *   <li>冷卻：熊咆的冷卻（可以花磐星跳過），插槽戰吼自己也有冷卻（還在冷卻就不能用；熊咆品質加快它的冷卻回復）。</li>
 * </ul>
 * 存檔：寶石放在熊咆的第 1 孔（{@code @代號/等級/孔數}，跟英靈圖騰、主要寶石一樣），舊的存檔沒放就照舊。純邏輯。
 */
public final class SocketedWarcry {

    private SocketedWarcry() {
    }

    /** 插槽戰吼增加的範圍（%）。 */
    public static final double AREA = 50;

    /** 插槽戰吼增加的傷害（%，熊咆 1～40 級；RePoE SupportFerociousRoarPlayer damage_+%）。被它強化的攻擊增加一樣多。 */
    static final double[] DAMAGE = {30, 33, 36, 39, 42, 45, 48, 51, 54, 57, 60, 63, 66, 69, 72, 75, 78, 81, 84, 87,
        90, 93, 96, 99, 102, 105, 108, 111, 114, 117, 118, 120, 121, 123, 124, 126, 127, 129, 130, 132};

    /** 熊咆這一級的插槽傷害（%）。 */
    public static double damage(final int level) {
        return DAMAGE[Math.max(1, Math.min(DAMAGE.length, level)) - 1];
    }

    /** 插槽戰吼的魔力消耗倍率（RePoE cost_multiplier：1 級 100%、2 級起 200%）。 */
    public static double costMultiplier(final int level) {
        return level <= 1 ? 1.0 : 2.0;
    }

    /** 這個技能的孔能不能放人形戰吼（裂甲熊咆：skills.yml 的 flag.socketed_warcry）。 */
    public static boolean hosts(final SkillDefinition skill) {
        if (skill == null || skill.warcry() == null) {
            return false;
        }
        for (final Modifier modifier : skill.modifiers()) {
            if (modifier.stat().equals(WarcryKeys.FLAG_SOCKETS_WARCRY) && modifier.value() > 0) {
                return true;
            }
        }
        return false;
    }

    /** 這顆技能能不能放進裂甲熊咆的孔；可以回傳 null，不行回傳原因。 */
    public static String problem(final SkillDefinition inner) {
        if (inner == null || inner.hidden()) {
            return "不認得的技能";
        }
        if (inner.mechanic() != Mechanic.WARCRY || inner.warcry() == null || !inner.warcry().cry() || !inner.tags().contains(Tags.WARCRY)) {
            return "只能放戰吼";
        }
        if (inner.tags().contains(Tags.SHAPESHIFT) || inner.tags().contains(Tags.META) || hosts(inner)) {
            return "只能放人形的戰吼（變身的戰吼、主要寶石不行）";
        }
        return null;
    }

    /**
     * 觸發時加在插槽戰吼上的修飾器（要在算範圍倍率之前放進去）：範圍增加 50%、傷害增加 x%、被強化的攻擊增加 x% 傷害，
     * 再加上熊咆品質裡給插槽戰吼的兩條（被強化的攻擊增加傷害、冷卻回復速度）。
     *
     * @param roarLevel   熊咆實際的等級
     * @param roarQuality 熊咆的品質修飾器（SkillService.qualityModifiers）
     */
    public static List<Modifier> modifiers(final int roarLevel, final List<Modifier> roarQuality) {
        final String source = "裂甲熊咆（插槽）";
        final List<Modifier> list = new ArrayList<>();
        list.add(Modifier.increased(StatKeys.SKILL_AREA, AREA / 100.0, source));
        list.add(Modifier.increased(StatKeys.DAMAGE, damage(roarLevel) / 100.0, source));
        double empowered = damage(roarLevel);
        for (final Modifier modifier : roarQuality) {
            if (modifier.stat().equals(WarcryKeys.SOCKETED_EMPOWERED_DAMAGE)) {
                empowered += modifier.value();
            } else if (modifier.stat().equals(WarcryKeys.SOCKETED_COOLDOWN_RECOVERY)) {
                list.add(new Modifier(StatKeys.COOLDOWN_RECOVERY, modifier.type(), modifier.value(), modifier.tags(), modifier.source()));
            }
        }
        list.add(Modifier.flat(WarcryKeys.EMPOWERED_DAMAGE, empowered, source));
        return list;
    }
}
