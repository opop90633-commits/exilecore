package tw.exilecore.skill;

/**
 * 2026-11-01 照 PoE2 0.5：技能對裝備的額外需求（skills.yml 的 weapons 只看主手，這裡補上主手以外的）：
 * <ul>
 *   <li>{@link #SHIELD}（weapons: [shield]）：PoE2 的 RequiresShield——盾牌衝鋒、共鳴之盾、盾牆。不限主手武器，副手要拿盾
 *       （PoB2 的圓盾 Buckler 也是 type Shield，算；法器不是盾）。這次之前這三招寫 weapons [mace]（要拿錘、副手的盾只拿來算附加傷害）。</li>
 *   <li>{@link #TWO_HAND_ONLY}（modifiers 寫 flag.two_hand_only）：只能用雙手武器（千鈞砸 weaponTypes 只有 Two Hand Mace）。</li>
 *   <li>{@link #NO_DUAL_WIELD}（modifiers 寫 flag.no_dual_wield）：PoE2 的 NoDualWield——雙持時不能用（千鈞砸）。</li>
 * </ul>
 */
public final class SkillGear {

    /** weapons 裡寫這個 = 要副手拿盾（不限主手）。 */
    public static final String SHIELD = "shield";
    /** 技能自己的旗標：只能用雙手武器（千鈞砸只能雙手錘）。 */
    public static final String TWO_HAND_ONLY = "flag.two_hand_only";
    /** 技能自己的旗標：雙持時不能用（PoE2 NoDualWield）。 */
    public static final String NO_DUAL_WIELD = "flag.no_dual_wield";

    /** 技能自己的「雙持時傷害更多 x%」（FLAT，寫百分比；PoE2 active_skill_damage_+%_final_while_dual_wielding，裂地斬 -30）。 */
    public static final String DUAL_WIELD_DAMAGE = SkillService.DUAL_WIELD_DAMAGE; // 同一條（SkillService.dualWieldDamage 算）
    /** 技能自己的「雙持時投射物數量加倍」（FLAT 1；PoE2 double_number_of_projectiles_from_dual_wielding，熔漿噴濺）。 */
    public static final String DUAL_WIELD_PROJECTILES = "skill.projectiles_double_dual_wield";

    private SkillGear() {
    }

    /** 這個技能要不要副手拿盾。 */
    public static boolean requiresShield(final SkillDefinition definition) {
        return definition != null && definition.weapons().contains(SHIELD);
    }

    /** 這件副手（物品的標籤）算不算 PoE2 的「盾」：有盾的標籤、不是法器（圓盾算：PoB2 Buckler 是 type Shield）。純函式（測試用）。 */
    public static boolean countsAsShield(final java.util.Set<String> offHandTags) {
        return offHandTags != null && offHandTags.contains("shield") && !offHandTags.contains("focus");
    }

    /**
     * 主手以外的需求不符的原因（可以用回傳 null）。純函式（測試用）：offHandTags 是副手那件的物品標籤（沒有拿東西是 null），
     * dualWielding = 主手、副手都拿武器（PlayerState.offHandAttack 不是 null），weaponTags = 主手武器的標籤。
     */
    public static String problem(final SkillDefinition definition, final java.util.Set<String> offHandTags, final boolean dualWielding,
                                 final java.util.Set<String> weaponTags) {
        if (definition == null) {
            return null;
        }
        if (requiresShield(definition) && !countsAsShield(offHandTags)) {
            return "要在副手裝盾才能用" + definition.name() + "（法器不行）";
        }
        if (SkillExecutor.hasFlag(definition, TWO_HAND_ONLY) && (weaponTags == null || !weaponTags.contains(tw.exilecore.stats.Tags.TWO_HAND))) {
            return definition.name() + "要拿雙手武器";
        }
        if (dualWielding && SkillExecutor.hasFlag(definition, NO_DUAL_WIELD)) {
            return definition.name() + "不能雙持使用";
        }
        return null;
    }

    /**
     * 2026-11-01 照 PoE2：雙持（主手、副手都拿武器）時技能自己的傷害更多／更少（{@link #DUAL_WIELD_DAMAGE}，只在
     * SkillService.dualWieldDamage 算一次）、投射物加倍（{@link #DUAL_WIELD_PROJECTILES}）。沒雙持、沒寫原樣回傳。
     * 攻擊速度的那一條在 SkillService.dualWieldSpeed。
     */
    static SkillBuild dualWield(final SkillBuild build, final boolean dualWielding) {
        if (!dualWielding || build == null) {
            return build;
        }
        boolean doubled = false;
        for (final tw.exilecore.stats.Modifier modifier : build.profile().local()) {
            if (DUAL_WIELD_PROJECTILES.equals(modifier.stat()) && modifier.value() > 0) {
                doubled = true;
            }
        }
        SkillBuild out = SkillService.dualWieldDamage(build, true);
        if (doubled) {
            out = out.withProjectilesSpread(out.projectiles() * 2, false);
        }
        return out;
    }

    /** 同上，從角色身上讀副手。 */
    static String problem(final tw.exilecore.ExileCore plugin, final tw.exilecore.character.PlayerState state, final SkillDefinition definition) {
        if (definition == null || state == null || !requiresShield(definition) && !SkillExecutor.hasFlag(definition, NO_DUAL_WIELD)
                && !SkillExecutor.hasFlag(definition, TWO_HAND_ONLY)) {
            return null;
        }
        java.util.Set<String> offTags = null;
        final org.bukkit.inventory.ItemStack off = state.jewellery(tw.exilecore.character.Gear.OFF);
        if (off != null && !off.isEmpty() && plugin.items() != null) {
            final tw.exilecore.item.ItemData data = plugin.items().read(off).orElse(null);
            final tw.exilecore.item.ItemBase base = data == null ? null : plugin.items().repository().base(data.base());
            offTags = base == null ? null : base.tags();
        }
        return problem(definition, offTags, state.offHandAttack() != null, state.weaponTags());
    }
}
