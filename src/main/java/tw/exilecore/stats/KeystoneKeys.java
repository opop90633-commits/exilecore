package tw.exilecore.stats;

import java.util.List;
import java.util.Set;

/**
 * 2026-10-26：PoE2 0.5 天賦樹「一次性的特殊效果」（stat_map 的 unique：基石、核心天賦）要用的屬性鍵與純計算。
 * 不放進 {@link StatKeys}，方便合併；Bukkit 那一邊（擊中、格擋、戰吼、藥劑…）在 {@code tw.exilecore.combat.KeystoneEffects}。
 * <p>
 * 每個鍵寫消費的地方。數值是百分比點數的寫 FLAT（30 = 30%），乘法的寫 INCREASED／MORE。
 */
public final class KeystoneKeys {

    private KeystoneKeys() {
    }

    // ---- 標籤 ----
    /** 這一下是暴擊（異常、破甲、暈眩累積的情境多這個標籤；「暴擊時…」的修飾器帶它）。CombatListener 補上。 */
    public static final String CRIT_HIT = "crit_hit";
    /** 風暴法術（PoE2 Storm：野旋風、積雷雲）。skills.yml 的技能帶這個標籤。 */
    public static final String STORM = "storm";

    // ---- 「最近…」的事件（TreeConditions.EVENTS；SkillService 出手時記） ----
    /** 最近用過攻擊技能。 */
    public static final String RECENT_ATTACK = "attackused";
    /** 最近施放過法術。 */
    public static final String RECENT_SPELL = "spellcast";

    // ---- 基石 ----
    /** 側身之擊（Glancing Blows）：閃避機率為不幸（擲兩次都要閃掉）、偏斜機率為幸運（擲兩次一次中就算）。DamageCalculator。 */
    public static final String FLAG_GLANCING = "flag.glancing_blows";
    /** 幻景綠洲（Oasis）：不能使用護符（CharmService）；藥劑回復更多 30%（MORE flask.*_recovery，tree.json 直接給）。 */
    public static final String FLAG_OASIS = "flag.oasis";
    /**
     * 巨人之血（Giant's Blood）：武鬥武器屬性需求三倍、力量給的固有生命減半（PlayerStateService）；
     * 可以單手持用雙手斧、錘、劍：副手配盾、法器，或再拿一把雙手斧、錘、劍雙持（Gear.giantOffHand、character.DualWield）。
     * 2026-10-27 照原作做出雙持；這次之前沒有雙持，副手空著時給「攻擊傷害更多 25%」（giants_blood.two_hand_more）當平衡等效，拿掉了。
     */
    public static final String FLAG_GIANTS_BLOOD = "flag.giants_blood";
    /** 能量連結（Conduit）：你要獲得戰星時，改給你存在範圍內的隊友（ChargeService）。 */
    public static final String FLAG_CONDUIT = "flag.conduit";
    /** 心靈永止（Heartstopper）：開始承受持續傷害的那 1 秒內更少 50%，之後更多 50%（AilmentService 的持續傷害）。 */
    public static final String FLAG_HEARTSTOPPER = "flag.heartstopper";
    /** 靈能魔符（Necromantic Talisman）：項鍊的所有加成改給召喚物（PlayerStateService 把項鍊的修飾器加上 minion 標籤）。 */
    public static final String FLAG_NECRO_TALISMAN = "flag.necromantic_talisman";
    /** 元素之相（Elemental Equilibrium）：獲得的灌注換一種：火→電、電→冰、冰→火（SkillResources）。 */
    public static final String FLAG_ELEMENTAL_EQUILIBRIUM = "flag.elemental_equilibrium";
    /** 創傷信仰（Scarred Faith）：能量護盾不回充、不再生、不會回到超過護甲（PlayerState 的回充／回復）。 */
    public static final String FLAG_SCARRED_FAITH = "flag.scarred_faith";
    /** 創傷信仰：每點敵人強度，抵銷的物理傷害有 x% 補償為能量護盾（KeystoneEffects）。 */
    public static final String SCARRED_RECOUP = "es.recoup_prevented_per_power";
    /**
     * 荒野領主（Lord of the Wilds）：持用獸魂符時副手可以再裝一把非傳奇權杖（2026-10-27 照原作：Gear.pairProblem／target、
     * PlayerStateService 的副手權杖；這次之前平衡等效給 100 精魂）。更少精魂、保留效率在 tree.json 直接給。
     */
    public static final String FLAG_LORD_OF_WILDS = "flag.lord_of_the_wilds";
    /** 空掌之術（Hollow Palm Technique）：兩手空著時當作拿長棍攻擊（PlayerStateService 的徒手）。 */
    public static final String FLAG_HOLLOW_PALM = "flag.hollow_palm";
    /** 祭祀終章（Ritual Cadence）：祈喚技能改成每 2 秒觸發，不會獲得能量（MetaGems）。 */
    public static final String FLAG_RITUAL_CADENCE = "flag.ritual_cadence";
    /** 原初飢餓（Primal Hunger）：怒氣沒有效果；最近每消耗 4 怒氣每秒再生 1 怒氣（SkillResources、CombatListener）。最大怒氣更多 100% 用 MORE rage.max。 */
    public static final String FLAG_PRIMAL_HUNGER = "flag.primal_hunger";
    /** FLAT：長棍技能消耗銳星時，多算幾顆（ChargeService.spend：用掉最後一顆時效果多算）。 */
    public static final String QUARTERSTAFF_EXTRA_POWER = "charge.quarterstaff_extra_power";
    /** FLAT：可以多施加幾個詛咒（滅世之語；Debuffs.applyCurse 的上限）。 */
    public static final String CURSE_LIMIT = "curse.limit";

    // ---- 傷害擲骰（DamageCalculator） ----
    /** FLAT %：擊中的傷害為幸運（擲兩次取高）的機率；可帶類型標籤（lightning）。 */
    public static final String LUCKY = "damage.lucky";
    /** FLAT %（防禦方）：擊中你的傷害為不幸（擲兩次取低）的機率。 */
    public static final String UNLUCKY_TAKEN = "damage.unlucky_taken";
    /** FLAT %：擊中把敵人的元素抗性當成相反數的機率（只翻正的）。 */
    public static final String INVERT_RESIST = "resist.invert_chance";
    /** FLAT %（防禦方）：用 200% 護甲防禦的機率。 */
    public static final String DOUBLE_ARMOUR = "armour.double_chance";
    /** FLAT %（防禦方）：受到的擊中物理傷害有 x% 改成隨機一種元素。 */
    public static final String PHYS_TAKEN_AS_RANDOM = "taken.physical_as_random";

    // ---- 受到擊中、格擋（KeystoneEffects） ----
    /** FLAT：格擋時獲得 x 能量護盾。 */
    public static final String ES_ON_BLOCK = "es.on_block";
    /** INCREASED：過去 10 秒每格擋一次，增加 x 移動速度（最多算 {@value #BLOCK_STACK_CAP} 次）。 */
    public static final String MOVE_PER_BLOCK = "movement.per_block";
    public static final int BLOCK_STACK_CAP = 20;
    /** FLAT %：受到的元素傷害有 x% 補償為生命（4 秒）。 */
    public static final String RECOUP_ELEMENTAL = "life.recoup_elemental";
    /** FLAT %：抵銷的物理傷害（護甲擋掉的）有 x% 補償為生命。 */
    public static final String RECOUP_PREVENTED = "life.recoup_prevented_physical";
    /** FLAT %：被偏斜的擊中受到的傷害有 x% 補償為生命。 */
    public static final String RECOUP_DEFLECTED = "life.recoup_deflected";
    /** FLAT %：受到的傷害有 x% 補償為能量護盾。 */
    public static final String ES_RECOUP = "es.recoup";
    /** FLAT %（主人的表）：召喚物受到的傷害有 x% 補償為牠的生命。 */
    public static final String MINION_RECOUP = "minion.recoup";

    // ---- 屬性表算完時換算（{@link #applyDerived}） ----
    /** FLAT %：獲得閃避的 x% 為額外護甲。 */
    public static final String EVASION_AS_ARMOUR = "armour.from_evasion";
    /** FLAT %：獲得等同力量 x% 的命中。 */
    public static final String ACCURACY_FROM_STR = "accuracy.from_str";
    /** 旗標：攻擊獲得等同暴擊率的增加命中（用武器的暴擊率）。 */
    public static final String ACCURACY_FROM_CRIT = "flag.accuracy_from_crit";
    /** INCREASED：頭盔上的能量護盾。 */
    public static final String HELMET_ES = "es.helmet";
    /** 旗標：獲得等同鞋子閃避與護甲較低者的異常門檻。 */
    public static final String BOOTS_AILMENT_THRESHOLD = "flag.boots_ailment_threshold";
    /** 旗標：獲得等同頭盔閃避與護甲較低者的暈眩門檻。 */
    public static final String HELMET_STUN_THRESHOLD = "flag.helmet_stun_threshold";
    /** FLAT %：獲得等同防具物品護甲 x% 的暈眩門檻。 */
    public static final String ARMOUR_STUN_THRESHOLD = "stun.threshold_from_item_armour";
    /** FLAT %：暈眩門檻有 x% 改照能量護盾算（不照生命；AilmentService 的玩家暈眩門檻）。 */
    public static final String STUN_ES_SHARE = "stun.threshold_es_share";
    /** 旗標：投射物速度的增減也套用在弓的傷害。 */
    public static final String PROJ_SPEED_BOW_DAMAGE = "flag.projectile_speed_bow_damage";
    /** INCREASED：要花生命的法術（血魔法、部分改扣生命、血祭術）的法術傷害。 */
    public static final String LIFE_COST_SPELL_DAMAGE = "damage.spell_costing_life";

    // ---- 戰吼（Warcries） ----
    /** FLAT：戰吼至少算這麼多強度。 */
    public static final String WARCRY_MIN_POWER = "warcry.min_power";
    /** FLAT %：戰吼時回復 x% 最大生命與魔力。 */
    public static final String WARCRY_RESTORE = "warcry.restore";
    /** 旗標：戰吼時解除你身上的點燃。 */
    public static final String WARCRY_REMOVE_IGNITE = "flag.warcry_remove_ignite";
    /** FLAT 秒：戰吼使範圍內的敵人衰弱（PoE2 Debilitate：造成的傷害減少 10%、移動速度減少 20%）。 */
    public static final String WARCRY_DEBILITATE = "warcry.debilitate";
    /** FLAT：戰吼給範圍內的敵人幾層暴擊弱點。 */
    public static final String WARCRY_CRIT_WEAKNESS = "warcry.crit_weakness";
    /** FLAT %：戰吼多強化 3 次攻擊的機率。 */
    public static final String WARCRY_EMPOWER3_CHANCE = "warcry.empower3_chance";

    // ---- 藥劑、引導 ----
    /** FLAT %：生命藥劑給 4 秒守護，等同每秒回復量的 x%。 */
    public static final String LIFE_FLASK_GUARD = "flask.life_guard";
    /** FLAT %：用魔力藥劑時回復 x% 最大生命。 */
    public static final String MANA_FLASK_LIFE = "flask.mana_flask_life";
    /** 旗標：用魔力藥劑時解除一個詛咒。 */
    public static final String MANA_FLASK_UNCURSE = "flag.mana_flask_uncurse";
    /** INCREASED：生命藥劑的回復速率（同樣的量、更短的時間）。FlaskService。 */
    public static final String FLASK_LIFE_RATE = "flask.life_rate";
    /** 旗標：引導 2 秒後解除一個詛咒。 */
    public static final String CHANNEL_UNCURSE = "flag.channel_uncurse";

    // ---- 擊中敵人（KeystoneEffects） ----
    /** 旗標：用長棍暴擊時擊退。 */
    public static final String KNOCKBACK_CRIT_QUARTERSTAFF = "flag.knockback_crit_quarterstaff";
    /** FLAT %：範圍技能擊中時擊退的機率。 */
    public static final String AREA_KNOCKBACK = "knockback.area_chance";
    /** FLAT %：感電的擊中有 x% 機率讓 1.5 格內的敵人也感電。 */
    public static final String SHOCK_SPREAD = "shock.spread_chance";
    /** FLAT %：擊殺時回滿生命的機率。 */
    public static final String KILL_FULL_LIFE = "kill.full_life_chance";
    /** FLAT %：擊殺中毒的敵人時回復 x% 最大生命。 */
    public static final String KILL_POISONED_LIFE = "kill.poisoned_life";
    /** FLAT %：荊棘傷害的 x% 偷取為生命。 */
    public static final String THORNS_LEECH = "thorns.leech";
    /** FLAT：地面技能同時存在的上限多幾個（帶標籤：fissure 裂地、orb 法球；SkillExecutor.zone）。 */
    public static final String SKILL_LIMIT = "skill.limit";
    /** 裂地技能（PoE2 Fissure：炎脈裂地、骨裂地脈）。 */
    public static final String FISSURE = "fissure";
    /** 法球技能（PoE2 Orb：雷雲、烈陽珠）。 */
    public static final String ORB = "orb";
    /** 旗標：你的光環增益不影響隊友（AuraService）。 */
    public static final String AURAS_SELF_ONLY = "flag.auras_self_only";
    /** FLAT 秒：每幾秒隨機獲得一顆戰星（PoE2 寶石匠 Thaumaturgical Dynamism；照力量／敏捷／智慧決定是磐星／怒星／銳星）。KeystoneEffects.tick。 */
    public static final String CHARGE_GENERATOR = "charge.generator_seconds";
    /** FLAT %：召喚物獲得最大生命的 x% 為額外最大能量護盾（MinionSheets）。 */
    public static final String MINION_LIFE_AS_ES = "minion.life_as_es";
    /** INCREASED：召喚物的生命回復速率（MinionSheets 的再生）。 */
    public static final String MINION_LIFE_RECOVERY = "minion.life_recovery";
    /** INCREASED：回響（法術自動重複的那一次）的範圍（SkillService 建回響時放大）。 */
    public static final String ECHO_AREA = "echo.area";
    /** 旗標：投射物穿透完全破甲的敵人（不算穿透次數；ProjectileEngine）。 */
    public static final String PIERCE_BROKEN = "flag.pierce_broken_armour";
    /** FLAT %：荊棘的基礎暴擊率（有這個荊棘才會暴擊；Thorns.profile）。 */
    public static final String THORNS_CRIT = "thorns.crit";
    /** FLAT %（主人的表）：召喚物破甲，等同造成的物理傷害 x%。 */
    public static final String MINION_BREAK = "minion.armour_break";
    /** 旗標：被你標記的敵人不能暴擊。 */
    public static final String MARKED_NO_CRIT = "flag.marked_no_crit";
    /** 旗標：被釘住的敵人不能暴擊。 */
    public static final String PINNED_NO_CRIT = "flag.pinned_no_crit";
    /** INCREASED（負的）：被你標記的敵人的命中。 */
    public static final String MARKED_ACCURACY = "marked.accuracy";
    /** 旗標：你流血的敵人不能再生生命。 */
    public static final String BLEED_NO_REGEN = "flag.bleeding_no_regen";
    /** 旗標：你完全破甲的敵人不能再生生命。 */
    public static final String BROKEN_NO_REGEN = "flag.broken_no_regen";

    // ---- 自己身上的異常（AilmentService） ----
    /** 旗標：身上已經有一種傷害型異常（點燃、流血、中毒）時，不會再中別種傷害型異常。 */
    public static final String ONE_DAMAGING_AILMENT = "flag.one_damaging_ailment";
    /** 旗標：被偏斜的擊中不會讓你流血。 */
    public static final String DEFLECT_NO_BLEED = "flag.deflect_no_bleed";
    /** 旗標：你造成的點燃會反射到自己身上。 */
    public static final String IGNITE_REFLECT = "flag.ignite_reflect";

    /** 一件防具：欄位（ItemSlot 的名稱）與它自己的護甲、閃避、能量護盾（含本地增加）。 */
    public record Piece(String slot, double armour, double evasion, double energyShield) {
    }

    /**
     * 屬性表算完（屬性、裝備都加好）時的換算：閃避→護甲、力量→命中、暴擊率→命中、頭盔護盾、鞋子／頭盔門檻、
     * 防具護甲→暈眩門檻、投射物速度→弓傷害、花生命的法術傷害、巨人之血的生命。
     *
     * @param pieces    生效的防具
     * @param strength  最後的力量（巨人之血：力量給的固有生命減半）
     * @param lifeFromStrength 力量給的固有生命
     * @param spellsCostLife   法術要花生命（血魔法、部分改扣生命、血祭術）
     */
    public static void applyDerived(final StatSheet sheet, final List<Piece> pieces, final double lifeFromStrength, final boolean spellsCostLife) {
        if (StatKeys.flag(sheet, FLAG_GIANTS_BLOOD) && lifeFromStrength > 0) {
            sheet.add(Modifier.flat(StatKeys.LIFE_MAX, -Math.floor(lifeFromStrength / 2), "巨人之血"));
        }
        final double helmetEs = sheet.increased(HELMET_ES, Set.of());
        final boolean bootsThreshold = StatKeys.flag(sheet, BOOTS_AILMENT_THRESHOLD);
        final boolean helmetStun = StatKeys.flag(sheet, HELMET_STUN_THRESHOLD);
        final double armourStun = sheet.flat(ARMOUR_STUN_THRESHOLD, Set.of());
        double itemArmour = 0;
        for (final Piece piece : pieces) {
            itemArmour += Math.max(0, piece.armour());
            if ("HEAD".equals(piece.slot())) {
                if (helmetEs != 0 && piece.energyShield() > 0) {
                    sheet.add(Modifier.flat(StatKeys.ES_MAX, Math.floor(piece.energyShield() * helmetEs), "頭盔護盾"));
                }
                if (helmetStun) {
                    sheet.add(Modifier.flat(StatKeys.STUN_THRESHOLD, Math.max(0, Math.min(piece.armour(), piece.evasion())), "頭盔"));
                }
            }
            if (bootsThreshold && "FEET".equals(piece.slot())) {
                sheet.add(Modifier.flat(StatKeys.AILMENT_THRESHOLD, Math.max(0, Math.min(piece.armour(), piece.evasion())), "鞋子"));
            }
        }
        if (armourStun > 0 && itemArmour > 0) {
            sheet.add(Modifier.flat(StatKeys.STUN_THRESHOLD, Math.floor(itemArmour * armourStun / 100.0), "防具護甲"));
        }
        final double evasionShare = sheet.flat(EVASION_AS_ARMOUR, Set.of());
        if (evasionShare > 0) {
            sheet.add(Modifier.flat(StatKeys.ARMOUR, Math.floor(sheet.get(StatKeys.EVASION) * evasionShare / 100.0), "閃避化甲"));
        }
        final double strShare = sheet.flat(ACCURACY_FROM_STR, Set.of());
        if (strShare > 0) {
            sheet.add(Modifier.flat(StatKeys.ACCURACY, Math.floor(sheet.get(StatKeys.STR) * strShare / 100.0), "力量"));
        }
        if (StatKeys.flag(sheet, ACCURACY_FROM_CRIT)) {
            // PoE2：攻擊獲得等同暴擊率的增加命中；命中不分技能，用武器（攻擊）的暴擊率
            final Set<String> attack = Set.of(Tags.ATTACK);
            final double crit = sheet.flat(StatKeys.WEAPON_CRIT, attack) * Math.max(0, 1 + sheet.increased(StatKeys.CRIT_CHANCE, attack))
                    * sheet.more(StatKeys.CRIT_CHANCE, attack);
            if (crit > 0) {
                sheet.add(Modifier.increased(StatKeys.ACCURACY, Math.min(100, crit) / 100.0, "暴擊率"));
            }
        }
        if (StatKeys.flag(sheet, PROJ_SPEED_BOW_DAMAGE)) {
            final double speed = sheet.increased(StatKeys.SKILL_PROJECTILE_SPEED, Set.of(Tags.ATTACK, Tags.PROJECTILE, Tags.BOW));
            if (speed != 0) {
                sheet.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, speed, Set.of(Tags.BOW), "投射物速度"));
            }
        }
        final double lifeSpell = sheet.increased(LIFE_COST_SPELL_DAMAGE, Set.of());
        if (spellsCostLife && lifeSpell != 0) {
            sheet.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, lifeSpell, Set.of(Tags.SPELL), "花生命的法術"));
        }
    }

    /** 巨人之血：武鬥武器（斧、錘、劍、矛、長棍、弓、弩、爪、匕首，法器以外的武器）的屬性需求三倍。 */
    public static boolean martial(final Set<String> weaponTags) {
        return weaponTags.contains(Tags.SWORD) || weaponTags.contains(Tags.AXE) || weaponTags.contains(Tags.MACE)
                || weaponTags.contains(Tags.SPEAR) || weaponTags.contains(Tags.QUARTERSTAFF) || weaponTags.contains(Tags.BOW)
                || weaponTags.contains(Tags.CROSSBOW) || weaponTags.contains(Tags.CLAW) || weaponTags.contains(Tags.DAGGER)
                || weaponTags.contains("flail");
    }

    /** 巨人之血的單手持雙手：雙手斧、錘、劍。 */
    public static boolean giantWeapon(final Set<String> weaponTags) {
        return weaponTags.contains(Tags.TWO_HAND)
                && (weaponTags.contains(Tags.AXE) || weaponTags.contains(Tags.MACE) || weaponTags.contains(Tags.SWORD));
    }

    /** 幸運／不幸：擲兩次取高／取低（第二次擲的值給進來，方便測試）。 */
    public static double luck(final double first, final double second, final boolean lucky, final boolean unlucky) {
        if (lucky == unlucky) {
            return first; // 同時幸運又不幸：互相抵銷（PoE2）
        }
        return lucky ? Math.max(first, second) : Math.min(first, second);
    }

    /** 不幸的閃避：擲兩次都要閃掉才算閃掉 → 命中率 1 − (1 − c)²。 */
    public static double unluckyEvadeHitChance(final double hitChance) {
        final double evade = 1 - Math.max(0, Math.min(1, hitChance));
        return 1 - evade * evade;
    }

    /** 幸運的機率：擲兩次一次中就算 → 1 − (1 − p)²。 */
    public static double luckyChance(final double chance) {
        final double miss = 1 - Math.max(0, Math.min(1, chance));
        return 1 - miss * miss;
    }

    /** 心靈永止：開始承受持續傷害之後幾 tick 內更少（1 秒）。 */
    public static final long HEARTSTOPPER_WINDOW = 20;

    /** 心靈永止的倍率：開始承受持續傷害的 1 秒內 0.5，之後 1.5。 */
    public static double heartstopper(final long sinceStart) {
        return sinceStart <= HEARTSTOPPER_WINDOW ? 0.5 : 1.5;
    }

    /** 元素之相：灌注換一種（0 火、1 冰、2 電）：火→電、電→冰、冰→火。 */
    public static int equilibrium(final int element) {
        return switch (element) {
            case 0 -> 2;
            case 2 -> 1;
            case 1 -> 0;
            default -> element;
        };
    }
}
