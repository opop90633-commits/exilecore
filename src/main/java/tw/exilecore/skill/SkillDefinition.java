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
 * @param damage        法術等級 1 的基礎傷害 [最小, 最大]，或逐級表攤平的 [等級 1 最小, 等級 1 最大, 等級 2 最小, …]（一般的攻擊為 null；
 *                      2026-10-27：不用武器的攻擊也寫這個，取代武器傷害，見 {@link #weaponless()}）
 * @param damageTypes   法術（與不用武器的攻擊）傷害的類型比例（總和 1）
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
 * @param typedDamage   2026-10-28 照 PoE2（纏蝕箭的物理、混沌各一張表）：每一種傷害自己的逐級表（攤平，同 damage 的寫法）；
 *                      寫了的話 damage 是各種加起來的表、damageTypes 是全部等級加總的比例（說明、網頁用），真正打的照這裡；沒寫是空的
 */
public record SkillDefinition(String id, String name, String description, String icon, SkillType type,
                              Set<String> tags, Set<String> weapons, String starter, boolean hidden, int tier, Mechanic mechanic,
                              double attackSpeed, double castTime, Scaled effectiveness, Scaled mana,
                              int[] damage, Map<DamageType, Double> damageTypes, Map<DamageType, Double> conversion,
                              SkillParams params, String visual, List<Modifier> modifiers, double cooldown, DeploySpec deploy,
                              SummonSpec summon, AuraSpec aura, WarcrySpec warcry, String cooldownBypass, String source,
                              Map<DamageType, int[]> typedDamage) {

    public SkillDefinition {
        modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
        source = source == null || source.isBlank() ? null : source.trim().toLowerCase(Locale.ROOT);
        typedDamage = typedDamage == null || typedDamage.isEmpty() ? Map.of() : Map.copyOf(typedDamage);
    }

    /** 同一個技能換一種特效（稜光裂每次施放的元素不同）。 */
    public SkillDefinition withVisual(final String newVisual) {
        return new SkillDefinition(id, name, description, icon, type, tags, weapons, starter, hidden, tier, mechanic, attackSpeed, castTime,
                effectiveness, mana, damage, damageTypes, conversion, params, newVisual, modifiers, cooldown, deploy, summon, aura, warcry,
                cooldownBypass, source, typedDamage);
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
            // 2026-10-18：地圖事件的巔峰 Boss
            case "shrine_warden" -> "晨曦神龕的神龕守望・晨曦";
            case "strongbox_mimic" -> "千鎖寶庫的貪匣・千鎖之主";
            case "breach_lord" -> "穿界裂隙的裂隙之主・穿界者";
            case "exile_king" -> "無名者競技場的流寇之王・無名者";
            case "ritual_priest" -> "血契祭場的祭司・血契";
            case "delirium_mirror" -> "妄念霧原的幻霧之鏡・妄念";
            case "abyss_lich" -> "骨潮深坑的淵底之王・骨潮";
            // 2026-10-26：法杖、長杖、權杖賦予的技能（PoE2 的 Grants Skill；沒有寶石）
            case "weapon" -> "法杖、長杖、權杖賦予";
            case "ascendancy" -> "昇華天賦賦予"; // 2026-10-26
            default -> "專屬的掉落來源";
        };
    }

    /** 是不是這個來源的專屬技能寶石。 */
    public boolean droppedBy(final String from) {
        return source != null && source.equals(from);
    }

    /**
     * 2026-10-27 照 PoE2（NonWeaponAttack）：不用武器的攻擊——攻擊技能自己寫了基礎傷害（damage），取代武器的傷害；
     * 帶 attack 標籤、吃攻擊的加成，不吃法術的加成、武器的傷害與武器的本地數值（暴擊率用 {@link tw.exilecore.stats.DamageCalculator#WEAPONLESS_BASE_CRIT}）。
     */
    public boolean weaponless() {
        return type == SkillType.ATTACK && damage != null;
    }

    /** 這個技能會不會直接造成傷害（純位移、印記、召喚、光環、戰吼不會：法術可以不寫 damage，說明也不列傷害）。 */
    public boolean dealsDamage() {
        for (final tw.exilecore.stats.Modifier modifier : modifiers) {
            if (modifier.stat().equals(tw.exilecore.stats.StatKeys.FLAG_TOTEM_TAUNT)) {
                return false; // 替身圖騰（2026-10）：不施法
            }
        }
        if (mechanic == Mechanic.AURA) {
            return false; // 2026-10-26：焚燒光環（焚心業火）拿掉了，光環都不直接造成傷害
        }
        if (mechanic == Mechanic.WARCRY) {
            return damage != null; // 2026-10（PoE2）：有寫 damage 的戰吼（震地戰吼）吼的時候會打範圍內的敵人
        }
        if (mechanic == Mechanic.GUARD) {
            return type == SkillType.ATTACK; // 2026-10-26 照 PoE2：架盾是攻擊（放開時的盾擊會打人）；其他舉盾、增益技能不打人
        }
        if (mechanic == Mechanic.MARK || mechanic == Mechanic.SUMMON || mechanic == Mechanic.RALLY || mechanic == Mechanic.META
                || mechanic == Mechanic.DESECRATE) {
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
        if (mechanic == Mechanic.MARK && !params.mark().curse()) {
            return ModifierText.tagName(Tags.MARK); // 2026-10-26 照 PoE2：印記不算法術
        }
        if (type == SkillType.SPELL && tags.contains(Tags.BUFF)) {
            return ModifierText.tagName(Tags.BUFF); // 2026-10-28 照 PoE2：增益技能（急裝）不算法術
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
        if (node.has("meta")) {
            tags.add(Tags.META); // 2026-10-22：主要寶石（星咒圖騰、暴擊引星…）：技能寶石放在它自己的孔（每一孔都能放）
        }
        tags.remove(type == SkillType.ATTACK ? Tags.SPELL : Tags.ATTACK);
        if (type == SkillType.SPELL && tags.contains(Tags.BUFF)) {
            // 2026-10-28 照 PoE2：增益技能（急裝：Buff、Empower）不算法術——法術的輔助（疾詠…）掛不上、帶 spell 標籤的加成不吃；
            // type 還是 spell（動作照施法時間算）。這次之前急裝算法術
            tags.remove(Tags.SPELL);
        }
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
        // 2026-10-17：坐騎（喚駒；2026-10-26 骨駒拿掉了）借 dash 實作，但不是衝過去：不算突進（躍電這種突進輔助掛不上）
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
            } else {
                tags.remove(Tags.SPELL); // 2026-10-26 照 PoE2：印記不算法術（詛咒才是），法術的輔助、帶 spell 標籤的加成不吃
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
            if (mechanic == Mechanic.AURA && !summon.permanent() && !onFire && summon.interval() <= 0) {
                throw new IllegalArgumentException(where + ".summon.duration：夥伴要是永久的（寫 0；每隔幾秒叫一隻的 interval 例外）");
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
            if (aura.herald()) { // 2026-10-26：焚心業火（常駐、不算光環）拿掉了，這次之前先判斷它
                // 捷光環（2026-10）：不算光環，另外一個標籤（光環的輔助、光環效果不吃；魔力保留的加成照吃）
                tags.remove(Tags.AURA);
                tags.add(Tags.HERALD);
            } else {
                // （2026-10-17 試過把引星這類 yml 寫 persistent 的拿掉光環標籤：精魂輔助、雕琢的分類會跟著掛不上，先維持算光環）
                tags.add(Tags.AURA);
            }
            // 隊友在範圍內才有效：範圍效果的加成讓它更大。2026-10-16：沒有任何效果會分給範圍內的人（引星：只是觸發別的技能）
            // 就不算範圍技能——擴散、凝聚這些範圍的輔助掛上去不會有作用，不要讓人誤會
            if (!aura.effects().isEmpty() || aura.enemy() != null || aura.onKill() != null) {
                tags.add(Tags.AREA);
            }
            // 2026-10：光環、常駐技能不算法術（照 PoE2：它們是「增益、常駐」）——法術的輔助掛不上、
            // 「+法術技能等級」與帶 spell 標籤的加成不吃。type 還是寫 spell：開光環的動作照施法時間算。
            // 捷光環例外：它的輔助會傳給擊殺效果（隱藏的法術），拿掉的話傷害類的輔助全部掛不上去
            if (!aura.herald()) {
                tags.remove(Tags.SPELL);
            }
            // 2026-10-26 照 PoE2：雷使、霜使是攻擊（擊殺效果打的是攻擊傷害；攻擊的輔助能掛、法術的不行）——yml 的 tags 寫 attack 的捷光環照攻擊算
            if (aura.herald() && lower(node.strings("tags")).contains(Tags.ATTACK)) {
                tags.remove(Tags.SPELL);
                tags.add(Tags.ATTACK);
            }
        }
        if (warcry != null) {
            // 2026-10-26 照 PoE2：熔魂嘯沒有冷卻，要榮耀才能用（warcry.glory）
            if (cooldown <= 0 && warcry.extras().glory() <= 0) {
                throw new IllegalArgumentException(where + "：戰吼要有 cooldown（冷卻時間）或 glory（要幾點榮耀）");
            }
            // 2026-10-26 照 PoE2：戰旗、祭品、召歸令不是戰吼（cry: false）：不帶戰吼標籤，戰吼的輔助掛不上、戰吼的加成不吃
            if (warcry.cry()) {
                tags.add(Tags.WARCRY);
            } else {
                tags.remove(Tags.WARCRY);
            }
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
                || mechanic == Mechanic.AURA
                || mechanic == Mechanic.WARCRY || mechanic == Mechanic.RALLY || mechanic == Mechanic.DESECRATE || mechanic == Mechanic.META
                || mechanic == Mechanic.GUARD
                || (mechanic == Mechanic.DASH && params.dash().hitWidth() <= 0 && params.dash().startRadius().last() <= 0
                && params.dash().endRadius().last() <= 0 && params.ground() == null);
        if (mechanic == Mechanic.SUMMON && node.has("damage")) {
            throw new IllegalArgumentException(where + ".damage：召喚技能自己不造成傷害（召喚物的傷害寫在 summon.damage，是同等級怪物的倍數）");
        }
        // 2026-10（PoE2）：戰吼可以寫 damage——吼的時候打半徑內的敵人一下（戰吼傷害：不算攻擊也不算法術，吃帶 warcry 標籤的加成）
        if (mechanic == Mechanic.AURA && node.has("damage")) {
            throw new IllegalArgumentException(where + ".damage：光環自己不造成傷害（捷的擊殺效果寫成隱藏技能，用 aura.on_kill 指過去）");
        }
        Map<DamageType, int[]> typedDamage = Map.of();
        if (node.has("damage_by_type")) {
            // 2026-10-28 照 PoE2（纏蝕箭：物理、混沌各一張逐級表）：每種傷害自己一張表；damage、damage_types 從這裡算出來，不能另外寫
            if (node.has("damage") || node.has("damage_types") || type != SkillType.SPELL) {
                throw new IllegalArgumentException(where + ".damage_by_type：只有法術能寫，而且不能再寫 damage、damage_types");
            }
            typedDamage = typedTables(where + ".damage_by_type", node.node("damage_by_type"));
            damage = summedTable(typedDamage);
            damageTypes = typedWeights(typedDamage);
        } else if (type == SkillType.SPELL && (!utility || node.has("damage"))) {
            damage = spellDamage(where + ".damage", node.raw("damage"));
            damageTypes = weights(where + ".damage_types", node.node("damage_types"), true);
            if (damageTypes.isEmpty()) {
                throw new IllegalArgumentException(where + "：法術要寫 damage_types（例如 { fire: 1.0 }）");
            }
        } else if (type == SkillType.ATTACK && node.has("damage")) {
            // 2026-10-27 照 PoE2（NonWeaponAttack：血爆、嗅血爆…）：攻擊寫了 damage 就是「不用武器的攻擊」——基礎傷害照這張表（取代武器），
            //   乘傷害效益；帶 attack 標籤（吃攻擊的加成），不帶武器的標籤、不吃武器的傷害與本地數值。這次之前攻擊不能寫 damage
            damage = spellDamage(where + ".damage", node.raw("damage"));
            damageTypes = weights(where + ".damage_types", node.node("damage_types"), true);
            if (damageTypes.isEmpty()) {
                throw new IllegalArgumentException(where + "：不用武器的攻擊（寫了 damage）要寫 damage_types（例如 { physical: 1.0 }）");
            }
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
        if (attackSpeed <= 0.1 || attackSpeed > 10) { // 2026-10-26：5 → 10（照 PoE2：熾息 800%）
            throw new IllegalArgumentException(where + ".attack_speed：要在 0.1 到 10 之間");
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
        if (effectiveness.min() <= 0 || mana.min() < 0) {
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
                params, visual, modifiers, cooldown, deploy, summon, aura, warcry, cooldownBypass, node.string("source", null), typedDamage);
        // 2026-10-16：不會造成傷害的技能（增益光環、印記、詛咒、戰吼、護體、引星…）不帶傷害類型的標籤：
        // 「冰冷傷害增加」這種本來就吃不到，「+冰冷技能等級」也不再算——不要讓人以為它吃元素的加成。
        // 召喚物（召喚物打的傷害類型）與捷（擊殺時的爆炸會打人）照留
        if (!parsed.dealsDamage() && summon == null && !tags.contains(Tags.HERALD) && tags.removeAll(DAMAGE_TYPE_TAGS)) {
            return new SkillDefinition(id, name, parsed.description(), parsed.icon(), type, Set.copyOf(tags), parsed.weapons(), parsed.starter(),
                    parsed.hidden(), tier, mechanic, attackSpeed, castTime, effectiveness, mana, damage, damageTypes, conversion,
                    params, visual, parsed.modifiers(), cooldown, deploy, summon, aura, warcry, cooldownBypass, parsed.source(), typedDamage);
        }
        return parsed;
    }

    /**
     * 法術的 damage：{@code [最小, 最大]}（等級 1 的值，再乘 spell_damage_growth），
     * 或逐級表 {@code [[最小, 最大], [最小, 最大], …]}（2026-10-26：照 PoE2 寶石表，從等級 1 開始每一級的實際傷害，不再乘成長倍率）。
     * 逐級表攤平成 {@code [等級 1 最小, 等級 1 最大, 等級 2 最小, …]}，所以 {@code damage[0..1]} 一樣是等級 1。
     */
    static int[] spellDamage(final String where, final Object raw) {
        if (raw instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof List<?>) {
            if (list.size() < 2 || list.size() > SkillBuild.MAX_EFFECTIVE_LEVEL) {
                throw new IllegalArgumentException(where + "：逐級表要寫 2～" + SkillBuild.MAX_EFFECTIVE_LEVEL + " 級");
            }
            final int[] flat = new int[list.size() * 2];
            for (int i = 0; i < list.size(); i++) {
                final int[] pair = YamlNode.toRange(where + "[" + (i + 1) + "]", list.get(i));
                checkDamage(where + "[" + (i + 1) + "]", pair);
                flat[i * 2] = pair[0];
                flat[i * 2 + 1] = pair[1];
            }
            return flat;
        }
        if (raw == null) {
            throw new IllegalArgumentException(where + "：缺少範圍欄位 damage");
        }
        final int[] pair = YamlNode.toRange(where, raw);
        checkDamage(where, pair);
        return pair;
    }

    /** damage_by_type：{ physical: [[最小, 最大], …], chaos: [[最小, 最大], …] }，每一種都要是逐級表，而且級數一樣。 */
    static Map<DamageType, int[]> typedTables(final String where, final YamlNode node) {
        final Map<DamageType, int[]> map = new EnumMap<>(DamageType.class);
        int length = -1;
        for (final String key : node.keys()) {
            final DamageType type = DamageType.fromTag(key);
            if (type == null) {
                throw new IllegalArgumentException(where + "：不認得的傷害類型「" + key + "」（physical、fire、cold、lightning、chaos）");
            }
            final int[] table = spellDamage(where + "." + key, node.raw(key));
            if (table.length <= 2) {
                throw new IllegalArgumentException(where + "." + key + "：要寫逐級表 [[最小, 最大], …]");
            }
            if (length >= 0 && table.length != length) {
                throw new IllegalArgumentException(where + "." + key + "：每一種的級數要一樣");
            }
            length = table.length;
            map.put(type, table);
        }
        if (map.isEmpty()) {
            throw new IllegalArgumentException(where + "：至少要一種傷害");
        }
        return Map.copyOf(map);
    }

    /** 各種傷害的逐級表加起來（說明、網頁、平衡表看的「總傷害」）。 */
    static int[] summedTable(final Map<DamageType, int[]> typed) {
        int[] sum = null;
        for (final int[] table : typed.values()) {
            if (sum == null) {
                sum = new int[table.length];
            }
            for (int i = 0; i < table.length; i++) {
                sum[i] += table[i];
            }
        }
        return sum;
    }

    /** 各種傷害占的比例（全部等級的平均傷害加總；只給說明、網頁看，真正打的照各自的表）。 */
    static Map<DamageType, Double> typedWeights(final Map<DamageType, int[]> typed) {
        final Map<DamageType, Double> map = new EnumMap<>(DamageType.class);
        double total = 0;
        for (final Map.Entry<DamageType, int[]> entry : typed.entrySet()) {
            double sum = 0;
            for (final int value : entry.getValue()) {
                sum += value;
            }
            map.put(entry.getKey(), sum);
            total += sum;
        }
        for (final Map.Entry<DamageType, Double> entry : map.entrySet()) {
            entry.setValue(total > 0 ? entry.getValue() / total : 1.0 / map.size());
        }
        return Map.copyOf(map);
    }

    private static void checkDamage(final String where, final int[] pair) {
        if (pair[0] < 0 || pair[1] < pair[0] || pair[1] <= 0) {
            throw new IllegalArgumentException(where + "：要寫 [最小, 最大]，最大要大於 0");
        }
    }

    /** damage 是不是逐級表（照 PoE2 的實際傷害，不乘成長倍率）。 */
    public boolean damageByLevel() {
        return damage != null && damage.length > 2;
    }

    /** 傷害類型的標籤。 */
    static final Set<String> DAMAGE_TYPE_TAGS = Set.of(Tags.PHYSICAL, Tags.FIRE, Tags.COLD, Tags.LIGHTNING, Tags.CHAOS, Tags.ELEMENTAL);

    /** { fire: 1.0, cold: 0.5 } 這種類型比例；normalize = true 時總和調成 1。 */
    static Map<DamageType, Double> weights(final String where, final YamlNode node, final boolean normalize) {
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
