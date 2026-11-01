package tw.exilecore.skill;

import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.SupportExtraKeys;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 2026-11-01 照 PoE2（SkillType.CommandableMinion；Path of Building PoE2 Data/Skills）：可以下指令的召喚物，與「指令技能的冷卻回復速度」。
 * <p>
 * 原作的 CommandableMinion 技能：六種骷髏（Brute、Sniper、Arsonist、Storm Mage、Frost Mage、Reaver）、Supporting Fire、
 * Spiraling Conspiracy（Mist Raven 的鴉群）、Kelari／Navira／Ruzhan 三位巨靈、Azmerian Wolf、Mist Raven。
 * 我們的遊戲裡有前 11 個（{@link #SKILLS}）；Azmerian Wolf、Mist Raven 沒有這兩個技能。
 * Kelari's Malediction（屍甲蟲）原作不是 CommandableMinion（軍令掛不上），骸骨戰士、祭司、狼、蟲、焚魂沒有指令。
 * <p>
 * 冷卻回復：原作是召喚物自己的 CooldownRecovery（INC，跟其他冷卻回復速度加在一起），帶「Condition: CommandableSkill」的只算指令技能
 * （天賦「召喚物的指令技能冷卻回復速度」、軍令的 minion_command_skill_cooldown_speed_+% 100）。我們的寫法：給召喚物屬性表的修飾器標
 * {@link #TAG}（天賦樹寫 tags: [minion, command]，給召喚物時拿掉 minion），查指令的冷卻時帶 {@link #CONTEXT}，
 * 召喚物自己其他招式的冷卻用空的情境查，吃不到。
 * <p>
 * 純邏輯，不依賴 Bukkit。
 */
public final class Commandables {

    /** 指令技能的情境標籤（技能 tags 寫 command 的就是指令：巨靈的指令、從天而降…）。 */
    public static final String TAG = "command";
    public static final Set<String> CONTEXT = Set.of(TAG);
    /** 軍令的修飾器來源名稱。 */
    static final String SOURCE = "軍令";

    /** 可以下指令的召喚物技能（軍令只能掛這些）。 */
    static final Set<String> SKILLS = Set.of(MinionAbilities.BRUTE, MinionAbilities.SNIPER, MinionAbilities.ARSONIST, MinionAbilities.STORM,
            MinionAbilities.FROST, MinionAbilities.REAVER, MinionAbilities.ARTILLERY, MinionAbilities.RAVENS,
            MinionAbilities.KELARI, MinionAbilities.NAVIRA, MinionAbilities.RUZHAN);
    /** 三位巨靈：牠們的指令都是法術；指令以外會造成傷害的只有攻擊（羅贊的狂熱斬擊）。 */
    static final Set<String> DJINNS = Set.of(MinionAbilities.KELARI, MinionAbilities.NAVIRA, MinionAbilities.RUZHAN);

    private Commandables() {
    }

    /** 這個技能叫出來的召喚物可不可以下指令。 */
    public static boolean commandable(final String skillId) {
        return skillId != null && SKILLS.contains(skillId);
    }

    /** 這些輔助裡有沒有軍令。 */
    public static boolean commandment(final List<SupportDefinition> supports) {
        if (supports == null) {
            return false;
        }
        for (final SupportDefinition support : supports) {
            if (support.flags(SupportExtraKeys.FLAG_COMMANDMENT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 軍令放進召喚物屬性表的修飾器：指令技能的冷卻回復速度增加 100%（標 command，只有指令吃得到）；
     * 巨靈另外加「攻擊（指令以外的招式）更少 100% 傷害」。骷髏、砲手的「指令以外更少 100%」照技能描述在 MinionService 判斷。
     * 沒掛軍令回傳空的。
     */
    public static List<Modifier> minionModifiers(final String skillId, final boolean commandment) {
        if (!commandment || !commandable(skillId)) {
            return List.of();
        }
        final List<Modifier> list = new ArrayList<>(2);
        list.add(new Modifier(StatKeys.COOLDOWN_RECOVERY, ModType.INCREASED, SupportExtraKeys.COMMANDMENT_RECOVERY, CONTEXT, SOURCE));
        if (DJINNS.contains(skillId)) {
            list.add(new Modifier(StatKeys.DAMAGE, ModType.MORE, -1.0, Set.of(Tags.ATTACK), SOURCE));
        }
        return list;
    }

    /** 召喚物屬性表上，指令技能的冷卻回復速度「增加」總和（一般的冷卻回復 ＋ 只給指令的；INC 相加）。 */
    public static double recovery(final StatSheet minionSheet) {
        return minionSheet == null ? 0 : minionSheet.increased(StatKeys.COOLDOWN_RECOVERY, CONTEXT);
    }

    /** 召喚物屬性表上，指令技能的冷卻回復速度倍率（(1 ＋ 增加) × 更多；最低 0.1）。 */
    public static double rate(final StatSheet minionSheet) {
        return minionSheet == null ? 1.0 : Math.max(0.1, minionSheet.apply(StatKeys.COOLDOWN_RECOVERY, 1.0, CONTEXT));
    }

    /** 冷卻（tick）：秒數 × 冷卻時間倍率 ÷ 冷卻回復速度倍率。 */
    public static long readyTicks(final double seconds, final double cooldownScale, final double recoveryRate) {
        return Math.round(seconds * Math.max(0.1, cooldownScale) * 20 / Math.max(0.1, recoveryRate));
    }

    /**
     * 沒有實體召喚物（{@code MinionService.Minion}）的可指令召喚物（鴉群密謀的烏鴉）用的屬性表：主人給召喚物的修飾器（帶 minion 標籤、
     * 沒有條件的；拿掉 minion）＋ 技能自己掛的輔助（local）＋ 軍令。只用來查冷卻，跟召喚物的屬性表同一套規則。
     */
    public static StatSheet ownerSide(final StatSheet owner, final List<Modifier> local, final String skillId, final boolean commandment) {
        final StatSheet sheet = new StatSheet();
        if (owner != null) {
            sheet.addAll(MinionSheets.stripMinion(MinionSheets.forMinion(MinionSheets.ownerModifiers(owner), Set.of())));
        }
        if (local != null) {
            sheet.addAll(MinionSheets.stripMinion(local));
        }
        sheet.addAll(minionModifiers(skillId, commandment));
        return sheet;
    }
}
