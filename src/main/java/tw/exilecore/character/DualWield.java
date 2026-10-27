package tw.exilecore.character;

import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 雙持（2026-10-27 照 PoE2）：副手也拿著一把能攻擊的武器時，攻擊輪流用主手、副手的武器出手——
 * 每一下的武器傷害、攻擊速度、暴擊率（武器的本地數值，屬性表上的 {@code weapon.*}）照出手的那一把算；
 * 武器上的全域詞綴（力量、生命、增加攻擊速度…）兩把都一直生效。PoE2 的雙持本身沒有額外的格擋、攻速或傷害加成。
 * <p>
 * 目前唯一能雙持的情形是基石・巨人之血：主手、副手都拿雙手斧、錘、劍（原作：可以單手持用雙手斧、錘、劍，所以兩手各拿一把）。
 * 這次之前沒有雙持，巨人之血副手空著時給「攻擊傷害更多 25%」當平衡等效（拿掉了）。
 * <p>
 * 做法：算屬性表時記下主手、副手武器的 {@code weapon.*}（{@link OffHand}）；施放攻擊時（SkillService）輪到副手，
 * 就在這一下的技能上疊「拿掉主手武器數值、換上副手武器數值」的修飾器（{@link SkillProfile#withLocal}），標籤換成副手武器的。
 */
public final class DualWield {

    private DualWield() {
    }

    /**
     * 副手出手要用的東西。
     *
     * @param tags     副手武器的種類標籤
     * @param mainTags 主手武器的種類標籤
     * @param swap     疊在技能上：主手武器數值的相反＋副手武器數值
     */
    public record OffHand(Set<String> tags, Set<String> mainTags, List<Modifier> swap) {
        public OffHand {
            tags = Set.copyOf(tags);
            mainTags = Set.copyOf(mainTags);
            swap = List.copyOf(swap);
        }
    }

    /** 武器本身的數值（基礎傷害、攻速、暴擊、裝填時間）：只有拿著它出手時才算。 */
    public static boolean weaponLocal(final Modifier modifier) {
        return modifier.stat().startsWith("weapon.");
    }

    /**
     * 換手的修飾器：主手武器的固定數值反過來（扣掉），再加上副手武器的。
     *
     * @param mainWeapon 主手武器的修飾器（只取 weapon.*）
     * @param offWeapon  副手武器的修飾器（只取 weapon.*）
     */
    public static List<Modifier> swap(final List<Modifier> mainWeapon, final List<Modifier> offWeapon) {
        final List<Modifier> list = new ArrayList<>();
        for (final Modifier modifier : mainWeapon) {
            if (weaponLocal(modifier) && modifier.type() == ModType.FLAT) {
                list.add(new Modifier(modifier.stat(), ModType.FLAT, -modifier.value(), modifier.tags(), "主手（副手出手）"));
            }
        }
        for (final Modifier modifier : offWeapon) {
            if (weaponLocal(modifier)) {
                list.add(new Modifier(modifier.stat(), modifier.type(), modifier.value(), modifier.tags(), "副手：" + modifier.source()));
            }
        }
        return list;
    }

    /** 副手的武器能不能拿來放這個攻擊（攻擊技能、而且技能能用副手那種武器）。 */
    public static boolean usable(final OffHand off, final boolean attack, final boolean usableWithOff) {
        return off != null && attack && usableWithOff;
    }

    /** 這一下改成副手出手：疊上換手的修飾器，標籤換成副手武器的。 */
    public static SkillProfile offHand(final SkillProfile profile, final OffHand off) {
        final Set<String> remove = new HashSet<>(off.mainTags());
        remove.removeAll(off.tags());
        final Set<String> add = new HashSet<>(off.tags());
        add.removeAll(off.mainTags());
        return profile.withLocal(off.swap()).derive(profile.id(), add, remove);
    }
}
