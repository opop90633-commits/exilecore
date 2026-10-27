package tw.exilecore.character;

import org.junit.jupiter.api.Test;
import tw.exilecore.combat.PlayerArmourBreak;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.KeystoneKeys;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-27 照 PoE2：巨人之血的雙持（兩手各拿一把雙手武器，輪流出手）、怪物破玩家的護甲；登入退點的保護、創傷信仰的護盾上限。 */
class DualWieldTest {

    private static final Set<String> MACE = Set.of("weapon", Tags.TWO_HAND, Tags.MACE, Tags.MELEE);
    private static final Set<String> SWORD = Set.of("weapon", Tags.TWO_HAND, Tags.SWORD, Tags.MELEE);

    private static List<Modifier> weapon(final double min, final double max, final double aps, final double crit) {
        return List.of(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, min, Set.of(Tags.PHYSICAL), "w"),
                new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, max, Set.of(Tags.PHYSICAL), "w"),
                Modifier.flat(StatKeys.WEAPON_APS, aps, "w"), Modifier.flat(StatKeys.WEAPON_CRIT, crit, "w"));
    }

    @Test
    void offHandSwingUsesTheOffHandWeapon() {
        final List<Modifier> main = weapon(40, 60, 1.1, 5);
        final List<Modifier> off = weapon(80, 120, 0.9, 7);
        final StatSheet sheet = new StatSheet();
        sheet.addAll(main);
        sheet.add(Modifier.increased(StatKeys.ATTACK_SPEED, 0.1, "全域"));
        final SkillProfile attack = SkillProfile.defaultAttack(MACE);
        final DualWield.OffHand hand = new DualWield.OffHand(SWORD, MACE, DualWield.swap(main, off));

        // 主手出手：主手的武器
        final StatSheet mainSheet = attack.effective(sheet);
        assertEquals(40, mainSheet.flat(StatKeys.WEAPON_MIN, Set.of(Tags.PHYSICAL)), 1e-9);
        assertEquals(1.1 * 1.1, DamageCalculator.attacksPerSecond(mainSheet, attack.tags()), 1e-9);
        // 副手出手：副手的武器傷害、攻速、暴擊；全域的增加攻擊速度照算；標籤換成劍
        final SkillProfile offAttack = DualWield.offHand(attack, hand);
        final StatSheet offSheet = offAttack.effective(sheet);
        assertEquals(80, offSheet.flat(StatKeys.WEAPON_MIN, Set.of(Tags.PHYSICAL)), 1e-9);
        assertEquals(120, offSheet.flat(StatKeys.WEAPON_MAX, Set.of(Tags.PHYSICAL)), 1e-9);
        assertEquals(7, offSheet.flat(StatKeys.WEAPON_CRIT, offAttack.tags()), 1e-9);
        assertEquals(0.9 * 1.1, DamageCalculator.attacksPerSecond(offSheet, offAttack.tags()), 1e-9);
        assertTrue(offAttack.tags().contains(Tags.SWORD));
        assertFalse(offAttack.tags().contains(Tags.MACE));
        assertTrue(offAttack.tags().contains(Tags.ATTACK));
    }

    @Test
    void handsAlternateAndOnlyAttacksUseTheOffHand() {
        final PlayerState state = new PlayerState(UUID.randomUUID());
        assertFalse(state.nextHandIsOff());
        assertTrue(state.nextHandIsOff());
        assertFalse(state.nextHandIsOff());
        final DualWield.OffHand hand = new DualWield.OffHand(SWORD, MACE, List.of());
        assertTrue(DualWield.usable(hand, true, true));
        assertFalse(DualWield.usable(hand, false, true), "法術不用副手");
        assertFalse(DualWield.usable(hand, true, false), "技能不能用副手那種武器（例如錘子技能、副手拿劍）");
        assertFalse(DualWield.usable(null, true, true));
    }

    @Test
    void handOnlyFlipsWhenTheAttackGoesOff() {
        // 2026-10-27：先看輪到哪一手（不換），真的出手才換；失敗（冷卻、沒魔力、太遠）的不換手
        final PlayerState state = new PlayerState(UUID.randomUUID());
        assertFalse(state.peekHandIsOff());
        assertFalse(state.peekHandIsOff(), "只看不換");
        state.flipHand();
        assertTrue(state.peekHandIsOff());
        state.flipHand();
        assertFalse(state.peekHandIsOff());
    }

    @Test
    void scarredFaithCapOnlyOnItsOwnRecoup() {
        // 2026-10-27：創傷信仰回的護盾（ES_CAPPED）不超過護甲；一般的護盾回復不夾
        assertEquals(300, Recoup.cappedEs(100, 200, 0, 50), 1e-9, "一般的回復不看護甲");
        assertEquals(150, Recoup.cappedEs(100, 0, 200, 150), 1e-9, "創傷信仰的回到護甲為止");
        assertEquals(300, Recoup.cappedEs(100, 200, 100, 150), 1e-9, "已經超過護甲：創傷信仰那份不加，也不拉低");
        final Recoup recoup = new Recoup();
        recoup.add(Recoup.ES_CAPPED, 80, 1);
        recoup.add(Recoup.ES, 40, 1);
        final double[] out = recoup.tick(Recoup.BASE_TICKS);
        assertEquals(80, out[Recoup.ES_CAPPED], 1e-9);
        assertEquals(40, out[Recoup.ES], 1e-9);
    }

    @Test
    void passivesTrimmedOnlyWhenPointSourcesLoaded() {
        // 2026-10-27：任務或昇華模組沒載入時可用點數少算，只警告不退點
        assertTrue(CharacterService.shouldTrim(3, true, true));
        assertFalse(CharacterService.shouldTrim(3, false, true));
        assertFalse(CharacterService.shouldTrim(3, true, false));
        assertFalse(CharacterService.shouldTrim(0, true, true));
    }

    @Test
    void bothGiantWeaponsAreMartialSoRequirementsTriple() {
        // 巨人之血：武鬥武器的屬性需求三倍——兩把雙手武器都是武鬥武器（PlayerStateService 對每一件生效的裝備套 ×3）
        assertTrue(KeystoneKeys.giantWeapon(MACE) && KeystoneKeys.martial(MACE));
        assertTrue(KeystoneKeys.giantWeapon(SWORD) && KeystoneKeys.martial(SWORD));
    }

    @Test
    void monstersBreakPlayerArmour() {
        // 粉碎的（1000%）：100 物理 → 破 1000 護甲；減少 50% 承受的破甲 → 500
        assertEquals(1000, PlayerArmourBreak.amount(100, 1000, 0), 1e-9);
        assertEquals(500, PlayerArmourBreak.amount(100, 1000, -0.5), 1e-9);
        assertEquals(0, PlayerArmourBreak.amount(100, 0, 0), 1e-9);
        final UUID player = UUID.randomUUID();
        assertFalse(PlayerArmourBreak.onHit(player, 600, 1000, 0));
        assertEquals(0.6, PlayerArmourBreak.lessFraction(PlayerArmourBreak.broken(player, 10), 1000), 1e-9);
        assertTrue(PlayerArmourBreak.onHit(player, 600, 1000, 20), "扣光：完全破甲");
        assertEquals(1.0, PlayerArmourBreak.lessFraction(PlayerArmourBreak.broken(player, 30), 1000), 1e-9);
        // 4 秒後恢復
        assertEquals(0, PlayerArmourBreak.broken(player, 20 + PlayerArmourBreak.DURATION_TICKS), 1e-9);
        final StatSheet unbreakable = new StatSheet().add(Modifier.increased(PlayerArmourBreak.TAKEN, -0.5, "堅不可破"));
        assertEquals(-0.5, PlayerArmourBreak.takenIncreased(unbreakable), 1e-9);
        PlayerArmourBreak.clear(player);
    }
}
