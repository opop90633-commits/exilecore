package tw.exilecore.skill;

import tw.exilecore.character.CharacterClass;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.AffixMod;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一個技能的定義（data/skills.yml）。純資料，不依賴 Bukkit。
 *
 * @param icon          面板上的圖示（原版物品名稱）
 * @param weapons       需要的武器標籤，任一符合即可；melee 包含徒手；空的 = 什麼武器都能用
 * @param starter       起始技能的職業代號（null = 不是）
 * @param hidden        普攻：玩家看不到、不能加進面板
 * @param tier          未切割技能寶石的等級要 ≥ 這個才能雕成這個技能（PoE2 的技能階級）
 * @param attackSpeed   攻擊技能的攻速倍率
 * @param castTime      法術的基礎施法時間（秒）
 * @param effectiveness 傷害效益（攻擊乘武器傷害；法術乘附加傷害）
 * @param mana          魔力消耗
 * @param damage        法術等級 1 的基礎傷害 [最小, 最大]（攻擊為 null）
 * @param damageTypes   法術傷害的類型比例（總和 1）
 * @param conversion    物理轉換成其他類型的比例（焚羽箭）
 * @param visual        外觀（粒子與音效）的代號
 * @param modifiers     技能自己帶的修飾器（例如熾星彈的點燃機率），跟輔助寶石一樣只對這個技能有效
 * @param cooldown      冷卻時間（秒；0 = 沒有）：位移、榴彈、印記這類技能用，放完要等
 * @param deploy        放下的東西（圖騰、陷阱、鐘）讓它放這個技能的運作方式；null = 自己放
 * @param summon        召喚物的設定（mechanic: summon 才有）；null = 不是召喚技能
 * @param aura          光環的設定（mechanic: aura 才有）；null = 不是光環
 * @param warcry        戰吼的設定（mechanic: warcry 才有）；null = 不是戰吼
 * @param cooldownBypass 冷卻中可以消耗一顆這種戰星直接放（charges.yml 的代號，例如瞬影擊的 frenzy）；null = 不行
 * @param source        專屬的掉落來源（例如 awakener：萬界之巔的虛星）：未切割技能寶石雕不出來，只有那裡會掉；null = 一般的
 */
public record SkillDefinition(String id, String name, String description, String icon, SkillType type,
                              Set<String> tags, Set<String> weapons, String starter, boolean hidden, int tier, Mechanic mechanic,
                              double attackSpeed, double castTime, Scaled effectiveness, Scaled mana,
                              int[] damage, Map<DamageType, Double> damageTypes, Map<DamageType, Double> conversion,
                              SkillParams params, String visual, List<Modifier> modifiers, double cooldown, DeploySpec deploy,
                              SummonSpec summon, AuraSpec aura, WarcrySpec warcry, String cooldownBypass, String source) {

    public SkillDefinition {
        modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
        source = source == null || source.isBlank() ? null : source.trim().toLowerCase(Locale.ROOT);
    }

    /** 同一個技能換一種特效（幻元箭每次施放的元素不同）。 */
    public SkillDefinition withVisual(final String newVisual) {
        return new SkillDefinition(id, name, description, icon, type, tags, weapons, starter, hidden, tier, mechanic, attackSpeed, castTime,
                effectiveness, mana, damage, damageTypes, conversion, params, newVisual, modifiers, cooldown, deploy, summon, aura, warcry,
                cooldownBypass, source);
    }

    /** 能不能從未切割技能寶石雕出來（專屬掉落的不行）。 */
    public boolean engravable() {
        return source == null;
    }

    /**
     * 專屬技能寶石從哪裡掉（玩家看的文字；2026-10-16：以前寫死「劫盜的大金庫」，現在專屬寶石都是萬界之巔的虛星掉）。
     * 一般的（雕得出來）回傳 null。
     */
    public String sourceText() {
        if (source == null) {
            return null;
        }
        return switch (source) {
            case "awakener" -> "萬界之巔的覺醒之主・虛星";
            case "heist" -> "劫盜的大金庫";
            case "abyss_whale" -> "星骸之海的淵鯨";
            default -> "專屬的掉落來源";
        };
    }

    /** 是不是這個來源的專屬技能寶石。 */
    public boolean droppedBy(final String from) {
        return source != null && source.equals(from);
    }

    /** 這個技能會不會直接造成傷害（純位移、印記、召喚、光環、戰吼不會：法術可以不寫 damage，說明也不列傷害）。 */
    public boolean dealsDamage() {
        for (final tw.exilecore.stats.Modifier modifier : modifiers) {
            if (modifier.stat().equals(tw.exilecore.stats.StatKeys.FLAG_TOTEM_TAUNT)) {
                return false; // 替身圖騰（2026-10）：不施法
            }
        }
        if (mechanic == Mechanic.AURA) {
            return aura != null && aura.burns(); // 焚燒光環（焚心業火）每半秒打周圍的敵人
        }
        if (mechanic == Mechanic.WARCRY) {
            return damage != null; // 2026-10（PoE2）：有寫 damage 的戰吼（震地戰吼）吼的時候會打範圍內的敵人
        }
        if (mechanic == Mechanic.MARK || mechanic == Mechanic.SUMMON || mechanic == Mechanic.RALLY || mechanic == Mechanic.META
                || mechanic == Mechanic.DESECRATE || mechanic == Mechanic.GUARD) {
            return false;
        }
        if (mechanic == Mechanic.DASH) {
            final SkillParams.Dash dash = params.dash();
            return dash.hitWidth() > 0 || dash.startRadius().last() > 0 || dash.endRadius().last() > 0 || params.ground() != null;
        }
        return true;
    }


    public boolean isAttack() {
        return type == SkillType.ATTACK;
    }

    /** 種類（說明用）：攻擊、法術；戰吼不算法術（2026-10，沒有 spell 標籤），寫「戰吼」。 */
    public String typeName() {
        if (summon != null) {
            return "召喚"; // 2026-10：召喚技能不算法術
        }
        if (mechanic == Mechanic.AURA) {
            // 2026-10：光環、捷光環、常駐技能不算法術——種類就寫「光環」「捷光環」「常駐」（type 寫 spell 只是開的動作照施法時間）
            return ModifierText.tagName(tags.contains(Tags.HERALD) ? Tags.HERALD : tags.contains(Tags.PERSISTENT) ? Tags.PERSISTENT : Tags.AURA);
        }
        return mechanic == Mechanic.WARCRY ? ModifierText.tagName(Tags.WARCRY) : type.displayName();
    }

    /** 手上的武器能不能用這個技能（徒手算近戰）。 */
    public boolean usableWith(final Set<String> weaponTags) {
        if (weapons.isEmpty()) {
            return true;
        }
        for (final String weapon : weapons) {
            if (weaponTags.contains(weapon) || (Tags.MELEE.equals(weapon) && weaponTags.isEmpty())) {
                return true;
            }
        }
        return false;
    }

    /** 需要的武器（給說明用）：「近戰武器」「弓」…。 */
    public String weaponText() {
        final StringBuilder builder = new StringBuilder();
        for (final String weapon : weapons) {
            if (!builder.isEmpty()) {
                builder.append("或");
            }
            builder.append(switch (weapon) {
                case Tags.MELEE -> "近戰武器（或徒手）";
                case Tags.BOW -> "弓";
                case Tags.WAND -> "法杖";
                case Tags.STAFF -> "長杖";
                case Tags.CROSSBOW -> "弩";
                case Tags.QUARTERSTAFF -> "長棍";
                case Tags.SPEAR -> "矛";
                case Tags.TALISMAN -> "獸魂符";
                case Tags.SWORD -> "劍";
                case Tags.AXE -> "斧";
                case Tags.MACE -> "錘";
                case Tags.DAGGER -> "匕首";
                case Tags.CLAW -> "爪";
                case Tags.ONE_HAND -> "單手武器";
                case Tags.TWO_HAND -> "雙手武器";
                case Tags.RANGED -> "遠程武器";
                default -> weapon;
            });
        }
        return builder.toString();
    }

    static SkillDefinition parse(final String id, final YamlNode node, final Set<String> visuals) {
        final String where = node.path();
        final SkillType type = SkillType.parse(where, node.requireString("type"));
        final Set<String> tags = new LinkedHashSet<>(lower(node.strings("tags")));
        tags.add(type == SkillType.ATTACK ? Tags.ATTACK : Tags.SPELL);
        tags.remove(type == SkillType.ATTACK ? Tags.SPELL : Tags.ATTACK);
        // 2026-10 標籤統一：沒有轉成元素、混沌的攻擊打的就是武器的物理傷害，一律算物理技能（以前有的有寫、有的沒寫）
        if (type == SkillType.ATTACK && java.util.Collections.disjoint(tags,
                Set.of(Tags.FIRE, Tags.COLD, Tags.LIGHTNING, Tags.CHAOS, Tags.ELEMENTAL))) {
            tags.add(Tags.PHYSICAL);
        }
        final Mechanic mechanic = Mechanic.parse(where, node.requireString("mechanic"));
        final SkillParams params = SkillParams.parse(mechanic, node);
        final double cooldown = node.decimal("cooldown", 0);
        if (cooldown < 0 || cooldown > 120) {
            throw new IllegalArgumentException(where + ".cooldown：要在 0 到 120 秒之間");
        }
        // 2026-10：光環、精魂召喚綁著就開著，不會施放，冷卻沒有作用：不算「有冷卻的技能」
        // （以前掛得上「爆發」這種要冷卻的輔助，白拿更多傷害、冷卻懲罰卻沒影響）
        if (cooldown > 0 && mechanic != Mechanic.AURA) {
            tags.add(Tags.COOLDOWN);
        }
        String cooldownBypass = node.string("cooldown_bypass", null);
        if (cooldownBypass != null) {
            cooldownBypass = cooldownBypass.trim().toLowerCase(Locale.ROOT);
            if (!cooldownBypass.matches("[a-z_]+")) {
                throw new IllegalArgumentException(where + ".cooldown_bypass：要寫戰星的代號（charges.yml，例如 frenzy），卻是「" + cooldownBypass + "」");
            }
            if (cooldown <= 0) {
                throw new IllegalArgumentException(where + ".cooldown_bypass：要有 cooldown（冷卻時間）才有東西可以跳過");
            }
        }
        if (mechanic == Mechanic.DASH || mechanic == Mechanic.LEAP || mechanic == Mechanic.FLICKER) {
            tags.add(Tags.MOVEMENT);
        }
        // 2026-10-17：坐騎（喚駒、骨駒）借 dash 實作，但不是衝過去：不算突進（躍電這種突進輔助掛不上）
        if ((mechanic == Mechanic.DASH || mechanic == Mechanic.LEAP) && !node.has("mount")) {
            tags.add(Tags.TRAVEL); // 一路衝過去、跳過去的（瞬影擊是瞬移到敵人身邊，不算）
        }
        if (params.zone() != null || params.ground() != null || params.vortex() != null || params.mark() != null) {
            tags.add(Tags.DURATION);
        }
        if (mechanic == Mechanic.MARK) {
            tags.add(params.mark().curse() ? "curse" : Tags.MARK); // 2026-10：範圍的是詛咒
            if (params.mark().curse()) {
                tags.add(Tags.AREA);
            }
        }
        if (mechanic == Mechanic.CORPSE) {
            tags.add(Tags.CORPSE);
        }
        if (mechanic == Mechanic.CHAIN) {
            tags.add("chain"); // 2026-10-17：連鎖機制一律標上連鎖（以前隱藏的連鎖技能沒寫）
        }
        if (mechanic == Mechanic.PROJECTILE && params.fuse() > 0) {
            tags.add(Tags.GRENADE);
        }
        final DeploySpec deploy = DeploySpec.parse(node, node.string("name", id));
        if (deploy != null) {
            if (mechanic == Mechanic.DASH || mechanic == Mechanic.LEAP || mechanic == Mechanic.MARK || mechanic == Mechanic.CORPSE
                    || mechanic == Mechanic.SUMMON || mechanic == Mechanic.AURA || mechanic == Mechanic.WARCRY || mechanic == Mechanic.FLICKER
                    || mechanic == Mechanic.RALLY || mechanic == Mechanic.DESECRATE || mechanic == Mechanic.GUARD || mechanic == Mechanic.META) {
                throw new IllegalArgumentException(where + "：位移、跳躍、瞬影、印記、骸爆、召喚、光環、戰吼不能交給圖騰、陷阱或鐘放");
            }
            tags.add(deploy.kind() == DeploySpec.Kind.TRAP ? Tags.TRAP : Tags.TOTEM);
            if (deploy.kind() == DeploySpec.Kind.BELL) {
                tags.add(Tags.BELL);
            }
            tags.add(Tags.DURATION);
        }
        final SummonSpec summon = SummonSpec.parse(node, node.string("name", id));
        // 2026-10：夥伴＝mechanic: aura 再寫 summon 區塊（開著就一直有一隻，保留精魂；死了過一下再出來）
        if (mechanic == Mechanic.SUMMON ? summon == null : summon != null && mechanic != Mechanic.AURA) {
            throw new IllegalArgumentException(where + "：mechanic: summon 要寫 summon 區塊（其他運作方式不能寫；夥伴是 aura 加 summon）");
        }
        if (summon != null) {
            if (type == SkillType.ATTACK) {
                throw new IllegalArgumentException(where + "：召喚技能要是法術（type: spell）");
            }
            tags.add(Tags.MINION);
            if (summon.corpse()) {
                tags.add(Tags.CORPSE); // 要屍體的召喚（喚屍）：褻瀆輔助可以掛
            }
            final boolean onFire = node.list("modifiers").stream()
                    .anyMatch(m -> tw.exilecore.stats.StatKeys.FLAG_SUMMON_ON_FIRE.equals(m.string("stat", "")));
            if (mechanic == Mechanic.AURA && !summon.permanent() && !onFire) {
                throw new IllegalArgumentException(where + ".summon.duration：夥伴要是永久的（寫 0）");
            }
            // 2026-10：召喚技能不算法術（法術的輔助掛不上、帶 spell 標籤的加成不吃）；type 還是 spell：召喚的動作照施法時間算
            tags.remove(Tags.SPELL);
            // 召喚物用近戰打：近戰的輔助寶石（蠻力）可以掛；主人自己的近戰加成不會給召喚物（只給帶 minion 標籤的）。
            // 2026-10：遠程的（骸骨射手、術士）標「遠程」不標投射物——牠們是直接打中的彈道，不是真的投射物：
            // 「+x 投射物技能等級」吃不到、穿透／分光這些投射物的輔助也掛不上（掛了也沒有作用）；召喚物打人時照樣算投射物攻擊（MinionSheets）
            tags.add(summon.isRanged() ? Tags.RANGED : Tags.MELEE);
            if (!summon.permanent()) {
                tags.add(Tags.DURATION);
            }
        }
        final AuraSpec aura = AuraSpec.parse(node);
        if ((aura == null) != (mechanic != Mechanic.AURA)) {
            throw new IllegalArgumentException(where + "：mechanic: aura 要寫 aura 區塊（其他運作方式不能寫）");
        }
        final WarcrySpec warcry = WarcrySpec.parse(node);
        if ((warcry == null) != (mechanic != Mechanic.WARCRY)) {
            throw new IllegalArgumentException(where + "：mechanic: warcry 要寫 warcry 區塊（其他運作方式不能寫）");
        }
        if ((aura != null || warcry != null) && type == SkillType.ATTACK) {
            throw new IllegalArgumentException(where + "：光環與戰吼要是法術（type: spell）");
        }
        if (aura != null && summon != null) {
            // 2026-10：精魂召喚（骸骨兵、狼伴…）不算光環、也不算範圍技能：光環的輔助、「光環技能等級」、範圍加成都不吃
            tags.remove(Tags.AURA);
            tags.remove(Tags.AREA);
        } else if (aura != null) {
            if (aura.persistent()) {
                // 常駐技能（焚心業火）：不算光環（光環的輔助、光環效果、保留魔力都不吃），放在面板上就一直生效
                tags.remove(Tags.AURA);
                tags.add(Tags.PERSISTENT);
            } else if (aura.herald()) {
                // 捷光環（2026-10）：不算光環，另外一個標籤（光環的輔助、光環效果不吃；魔力保留的加成照吃）
                tags.remove(Tags.AURA);
                tags.add(Tags.HERALD);
            } else {
                // （2026-10-17 試過把引星這類 yml 寫 persistent 的拿掉光環標籤：精魂輔助、雕琢的分類會跟著掛不上，先維持算光環）
                tags.add(Tags.AURA);
            }
            // 隊友在範圍內才有效：範圍效果的加成讓它更大。2026-10-16：沒有任何效果會分給範圍內的人（引星：只是觸發別的技能）
            // 就不算範圍技能——擴散、凝聚這些範圍的輔助掛上去不會有作用，不要讓人誤會
            if (!aura.effects().isEmpty() || aura.burn() || aura.enemy() != null || aura.onKill() != null) {
                tags.add(Tags.AREA);
            }
            // 2026-10：光環、常駐技能不算法術（照 PoE2：它們是「增益、常駐」）——法術的輔助掛不上、
            // 「+法術技能等級」與帶 spell 標籤的加成不吃。type 還是寫 spell：開光環的動作照施法時間算。
            // 捷光環例外：它的輔助會傳給擊殺效果（隱藏的法術），拿掉的話傷害類的輔助全部掛不上去
            if (!aura.herald()) {
                tags.remove(Tags.SPELL);
            }
        }
        if (warcry != null) {
            if (cooldown <= 0) {
                throw new IllegalArgumentException(where + "：戰吼要有 cooldown（冷卻時間）");
            }
            tags.add(Tags.WARCRY);
            tags.add(Tags.AREA);
            tags.add(Tags.DURATION);
            // 戰吼不算法術（2026-10）：法術的輔助寶石（疾詠、雙詠、聚銳……）掛不上去，帶 spell 標籤的加成也不吃。
            // type 還是寫 spell：吼的動作照施法時間與施法速度算，不看武器攻速
            tags.remove(Tags.SPELL);
        }
        final String starter = node.string("starter", null);
        if (starter != null && CharacterClass.parse(starter, null) == null) {
            throw new IllegalArgumentException(where + ".starter：不認得的職業「" + starter + "」（" + CharacterClass.idList() + "）");
        }
        final String visual = node.requireString("visual");
        if (!visuals.contains(visual)) {
            throw new IllegalArgumentException(where + ".visual：不認得「" + visual + "」，可用：" + String.join("、", visuals.stream().sorted().toList()));
        }

        int[] damage = null;
        Map<DamageType, Double> damageTypes = Map.of();
        final boolean utility = mechanic == Mechanic.MARK || mechanic == Mechanic.SUMMON
                || mechanic == Mechanic.AURA && (aura == null || !aura.burns())
                || mechanic == Mechanic.WARCRY || mechanic == Mechanic.RALLY || mechanic == Mechanic.DESECRATE || mechanic == Mechanic.META
                || mechanic == Mechanic.GUARD
                || (mechanic == Mechanic.DASH && params.dash().hitWidth() <= 0 && params.dash().startRadius().last() <= 0
                && params.dash().endRadius().last() <= 0 && params.ground() == null);
        if (mechanic == Mechanic.SUMMON && node.has("damage")) {
            throw new IllegalArgumentException(where + ".damage：召喚技能自己不造成傷害（召喚物的傷害寫在 summon.damage，是同等級怪物的倍數）");
        }
        // 2026-10（PoE2）：戰吼可以寫 damage——吼的時候打半徑內的敵人一下（戰吼傷害：不算攻擊也不算法術，吃帶 warcry 標籤的加成）
        if (mechanic == Mechanic.AURA && (aura == null || !aura.burns()) && node.has("damage")) {
            throw new IllegalArgumentException(where + ".damage：光環自己不造成傷害（捷的擊殺效果寫成隱藏技能，用 aura.on_kill 指過去）");
        }
        if (type == SkillType.SPELL && (!utility || node.has("damage"))) {
            damage = node.range("damage");
            if (damage[0] < 0 || damage[1] < damage[0] || damage[1] <= 0) {
                throw new IllegalArgumentException(where + ".damage：要寫 [最小, 最大]，最大要大於 0");
            }
            damageTypes = weights(where + ".damage_types", node.node("damage_types"), true);
            if (damageTypes.isEmpty()) {
                throw new IllegalArgumentException(where + "：法術要寫 damage_types（例如 { fire: 1.0 }）");
            }
        } else if (type == SkillType.ATTACK && node.has("damage")) {
            throw new IllegalArgumentException(where + ".damage：攻擊技能用武器傷害，不寫 damage（用 effectiveness 調）");
        }
        final Map<DamageType, Double> conversion = weights(where + ".convert", node.node("convert"), false);
        double converted = 0;
        for (final double value : conversion.values()) {
            converted += value;
        }
        if (conversion.containsKey(DamageType.PHYSICAL) || converted > 1.0 + 1e-9) {
            throw new IllegalArgumentException(where + ".convert：只能把物理轉成其他類型，總和最多 1");
        }
        // 2026-10-17 標籤統一：部分轉換的攻擊，剩下的物理還有一半以上就算物理技能（[物理]標籤、鐵髓這類物理輔助掛得上）
        if (type == SkillType.ATTACK && !conversion.isEmpty() && converted <= 0.5 + 1e-9) {
            tags.add(Tags.PHYSICAL);
        }

        final double attackSpeed = node.decimal("attack_speed", 1.0);
        final double castTime = node.decimal("cast_time", 0.75);
        if (attackSpeed <= 0.1 || attackSpeed > 5) {
            throw new IllegalArgumentException(where + ".attack_speed：要在 0.1 到 5 之間");
        }
        if (castTime < 0.1 || castTime > 5) {
            throw new IllegalArgumentException(where + ".cast_time：要在 0.1 到 5 秒之間");
        }
        final int tier = node.integer("tier", 1);
        if (tier < 1 || tier > SkillLoadout.MAX_LEVEL) {
            throw new IllegalArgumentException(where + ".tier：要在 1 到 " + SkillLoadout.MAX_LEVEL + " 之間");
        }
        final Scaled effectiveness = Scaled.parse(where + ".effectiveness", node.raw("effectiveness"), 1.0);
        final Scaled mana = Scaled.parse(where + ".mana", node.raw("mana"), 0);
        if (effectiveness.first() <= 0 || effectiveness.last() <= 0 || mana.first() < 0 || mana.last() < 0) {
            throw new IllegalArgumentException(where + "：effectiveness 要大於 0，mana 不能是負的");
        }
        final String name = node.requireString("name");
        final List<Modifier> modifiers = new ArrayList<>();
        for (final YamlNode child : node.list("modifiers")) {
            modifiers.add(AffixMod.parse(child).toModifier(Map.of(), name));
        }
        final SkillDefinition parsed = new SkillDefinition(id, name, node.string("description", ""),
                node.string("icon", "BOOK"), type, Set.copyOf(tags), Set.copyOf(lower(node.strings("weapons"))),
                starter == null ? null : CharacterClass.parse(starter, null).id(), node.bool("hidden", false), tier, mechanic,
                attackSpeed, castTime, effectiveness, mana, damage, damageTypes, conversion,
                params, visual, modifiers, cooldown, deploy, summon, aura, warcry, cooldownBypass, node.string("source", null));
        // 2026-10-16：不會造成傷害的技能（增益光環、印記、詛咒、戰吼、護體、引星…）不帶傷害類型的標籤：
        // 「冰冷傷害增加」這種本來就吃不到，「+冰冷技能等級」也不再算——不要讓人以為它吃元素的加成。
        // 召喚物（召喚物打的傷害類型）與捷（擊殺時的爆炸會打人）照留
        if (!parsed.dealsDamage() && summon == null && !tags.contains(Tags.HERALD) && tags.removeAll(DAMAGE_TYPE_TAGS)) {
            return new SkillDefinition(id, name, parsed.description(), parsed.icon(), type, Set.copyOf(tags), parsed.weapons(), parsed.starter(),
                    parsed.hidden(), tier, mechanic, attackSpeed, castTime, effectiveness, mana, damage, damageTypes, conversion,
                    params, visual, parsed.modifiers(), cooldown, deploy, summon, aura, warcry, cooldownBypass, parsed.source());
        }
        return parsed;
    }

    /** 傷害類型的標籤。 */
    static final Set<String> DAMAGE_TYPE_TAGS = Set.of(Tags.PHYSICAL, Tags.FIRE, Tags.COLD, Tags.LIGHTNING, Tags.CHAOS, Tags.ELEMENTAL);

    /** { fire: 1.0, cold: 0.5 } 這種類型比例；normalize = true 時總和調成 1。 */
    private static Map<DamageType, Double> weights(final String where, final YamlNode node, final boolean normalize) {
        final Map<DamageType, Double> map = new EnumMap<>(DamageType.class);
        double total = 0;
        for (final String key : node.keys()) {
            final DamageType type = DamageType.fromTag(key);
            if (type == null) {
                throw new IllegalArgumentException(where + "：不認得的傷害類型「" + key + "」（physical、fire、cold、lightning、chaos）");
            }
            final double value = node.decimal(key, 0);
            if (value < 0) {
                throw new IllegalArgumentException(where + "." + key + "：不能是負的");
            }
            if (value > 0) {
                map.put(type, value);
                total += value;
            }
        }
        if (normalize && total > 0) {
            for (final Map.Entry<DamageType, Double> entry : map.entrySet()) {
                entry.setValue(entry.getValue() / total);
            }
        }
        return map.isEmpty() ? Map.of() : Map.copyOf(map);
    }

    private static List<String> lower(final List<String> list) {
        return list.stream().map(text -> text.trim().toLowerCase(Locale.ROOT)).filter(text -> !text.isEmpty()).toList();
    }
}
