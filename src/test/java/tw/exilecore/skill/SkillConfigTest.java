package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tw.exilecore.character.CharacterClass;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** data/skills.yml 與技能的數值（等級、傷害、間隔）。 */
class SkillConfigTest {

    /** 2026-10-26：照 PoE2 不花魔力的技能。 */
    private static final Set<String> POE2_FREE = Set.of("bear_maul", "wolf_shred", "wyvern_rend", "sun_orb",
            "raise_guard", "ruin_mantra", "mana_storm"); // 2026-10-26 魔湧（Mana Tempest）沒有施放消耗，風暴每秒扣最大魔力 1%； // 2026-10-26 照 PoE2：架盾（Raise Shield）是不花魔力的引導、崩滅心咒（Mantra of Destruction）不花魔力；2026-10-26 站長把焚燼併入焚流（這次之前也列焚燼）

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
    }

    @Test
    void shippedSkillsLoad() {
        // 2026-10 PoE2 改版：技能大改（250 個看得到的技能），這裡抽各種武器、運作方式的代表
        final List<String> visible = config.visible().stream().map(SkillDefinition::id).toList();
        // 2026-10-29：＋咒環（Blasphemy）、夏烏拉之手（Hand of Chayula）
        assertEquals(272 + 14 + 1 + 1 + 2 + 27 - 7 + 5 + 1 - 8 - 6 - 3 + 2 - 2 + 3 - 3 + 44 - 7 + 7 + 1 + 1 + 1 + 1 + 1, visible.size(), "2026-10-29：＋克拉里的殘酷（Kelari's Brutality）；＋夏烏拉之手（Hand of Chayula）；＋咒環（Blasphemy）；2026-10-28 照 PoE2：＋新生之儀（綺夢賦予的 Rite of Restoration）；2026-10-27 照 PoE2：＋每把矛賦予的擲矛（Spear Throw）；2026-10-27 照 PoE2 0.5：＋靈狩者、鳴鐘武師、淵井骸主賦予的 7 個技能；2026-10-27 照 PoE2：拿掉狂戰之怒、召歸令、4 個指令技能，掘骸併進骨棘陣（－7）；2026-10-26 照 PoE2：＋昇華天賦賦予的 44 個技能（沒有寶石）；2026-10-26：照站長刪掉原作沒有的血牙、元素脈衝、匯元衝擊（－3）；引星照 PoE2 0.5：－受創引星、斬殺引星，＋元祈、刈祈、障祈引星；＋弦月爪、翼刃斬（照 PoE2 0.5 補上德魯伊的 Lunar Assault、Rend）；照站長把矢幕、爆破矢、焚燼併入連珠、爆焰矢、焚流（跟原本的同一顆原作寶石，－3）；照站長拿掉霜綻、斬風旋、幻元擊、幻元箭、4 根法術圖騰（－8），骨駒、鐵蹄踐踏、霜流、雷鞭、星律、幻身步（－6）；＋裂甲熊咆（照 PoE2 的 Ferocious Roar）；＋咒擲、封釋、雷霆共鳴、折光之鏡、鴉群密謀；－7 個跟原作重複的自訂技能（凝霜星核、雷域、撼天重砸、腐霧矢、掃退、突刺衝鋒、裂虛星）；＋法杖、長杖、權杖賦予的 27 個技能；2026-10-24：＋幻身、招架；2026-10-18：＋召歸令；＋七隻事件巔峰 Boss 各 2 顆專屬技能寶石；250 個 + 巔峰 Boss 的 6 個專屬技能寶石 + 2026-10 主要寶石 7 個（星咒圖騰、6 個引星）、星律、斬風旋、幻元擊、4 根法術圖騰、幻元箭 + 淵鯨的喚潮");
        assertTrue(visible.containsAll(List.of("leap_slam", "earthquake", "boneshatter", "ancestral_totem", "flicker_strike",
                "caustic_arrow", "rain_of_arrows", "lightning_arrow", "ice_shot", "tornado_shot",
                "explosive_bolt", "permafrost_bolts", "rapid_fire", "explosive_grenade", "gas_grenade",
                "tempest_flurry", "ice_strike", "storm_wave", "killing_palm", "tempest_bell",
                "lightning_spear", "whirling_slash", "whirlwind_lance", "rake", "hunters_mark",
                "fireball", "frost_nova", "spark", "arc", "comet", "flame_wall", "shockwave_totem",
                "essence_drain", "contagion", "detonate_dead", "raise_zombie", "summon_skeletons", "raging_spirits",
                "herald_of_ash", "herald_of_thunder", "herald_of_ice", "fortifying_cry", "infernal_cry", "seismic_cry", "bear_maul")),
                visible.toString());
        assertEquals("leap_slam", visible.getFirst(), "照檔案裡的順序");
        // 2026-10-29：＋咒環（Blasphemy）
        assertEquals(344 + 14 + 1 + 1 + 2 + 8 + 27 + 2 + 6 - 7 + 7 + 1 + 6 + 1 + 1 - 9 - 7 - 3 + 2 - 2 + 3 + 10 - 3 + 1 + 64 + 3 - 7 + 3 + 11 + 4 - 1 + 5 + 1 + 1 + 6 + 3 - 2 + 1 - 1 + 1 + 1 + 1 + 2, config.all().size(), "2026-10-29：＋夏烏拉之手（Hand of Chayula）；＋咒環（Blasphemy）；2026-10-28 照 PoE2 Shockchain Arrow：跳電震波（隱藏）改成電鍊箭矢自己的震波段（－1）；2026-10-28 照 PoE2 Falling Thunder：＋驚雷劈用掉銳星射出的投射物（隱藏）；2026-10-28 照站長刪掉震符印、符刃：拿掉觸發的震符、符刃環（－2）；2026-10-28 照 PoE2：＋雷暴震波、它的感電地面、霜牙刺的冰緩地面（3 個隱藏技能，StageLeftovers）；2026-10-28 照 PoE2 攻擊的第二段：＋6 個隱藏技能（交錯震波、寒錐爆、雷鹿靈、瘴苞爆、凍壁迸裂、迸片碎凍）；2026-10-28 照 PoE2 Staggering Palm：＋震脈掌增益期間長棍攻擊多射的投射物（隱藏）；2026-10-28 照 PoE2 Snipe：＋穿雲箭用掉凍結時的寒冰爆破（隱藏）；2026-10-27 照站長刪掉活雷 II：拿掉它觸發的活雷電（−1）；2026-10-27 照 PoE2 的矛技能：＋擲矛與 4 個隱藏的爆炸（擲矛炸裂、炎爆矛炸裂、炎爆矛（怒星）、嗅血爆）；2026-10-27 照 PoE2 0.5 補上的一般輔助：＋4 個隱藏的觸發技能（活雷電、刺爆針、踏地震波、決殺斬）；2026-10-27 照 PoE2 0.5：＋靈狩者、鳴鐘武師、淵井骸主賦予的 7 個技能與 4 個隱藏的；2026-10-27 照 PoE2：拿掉狂戰之怒、召歸令、4 個指令技能、掘骸（－7），＋骨構體、鐵壁守與瘟囊爆放出的隱藏技能（＋3）；2026-10-27 照 PoE2：＋元素湧動的 3 個湧動爆炸（隱藏，這次之前沒有另外一發爆炸）；2026-10-26 照 PoE2：＋昇華天賦賦予的 44 個技能與 20 個隱藏的觸發；2026-10-26：照站長刪掉血牙、元素脈衝、匯元衝擊（－3）；＋催綻的綻花爆（隱藏，照 PoE2 Accelerated Growth）；＋10 個照 PoE2 原作機制的隱藏技能（OriginalSkills）；引星照 PoE2 0.5：－受創引星、斬殺引星，＋元祈、刈祈、障祈引星；＋弦月爪、翼刃斬（照 PoE2 0.5 補上德魯伊的 Lunar Assault、Rend）；照站長把矢幕、爆破矢、焚燼併入連珠、爆焰矢、焚流（－3）；照站長拿掉霜綻（含隱藏的霜綻冰地）、斬風旋、幻元擊、幻元箭、4 根法術圖騰（－9），骨駒、鐵蹄踐踏、霜流、雷鞭、星律、幻身步（含隱藏的殘影）（－7）；＋瘴苞雨的毒苞爆（隱藏，照 PoE2 Toxic Growth）；＋引霆突完美突進的震波（隱藏，照 PoE2 Gathering Storm）；＋裂甲熊咆與戰吼的 5 個隱藏技能（燃爆、盾波、火山步、火山噴發、扭曲契約）；＋死亡風暴的感電地面（隱藏）；＋5 個賦予技能、鴉群、感電地面（隱藏）；－7 個重複的自訂技能；＋武器賦予的 27 個、跳電震波與碎冰爆、6 個弩的隱藏技能（伏霆爆、冰晶迸、雷暴脈衝、雷屑光束、兩個碎裂錐形）；2026-10-24：＋幻身、招架與六個隱藏的（銳星、引雷震波、幻身、熔岩噴射、回槍震波、招架橫掃）；＋雷星的兩個游雷（隱藏）；2026-10-18：＋召歸令；＋14 顆事件巔峰的專屬；272 個技能 + 7 種普攻 + 65 個隱藏的效果（使者、觸發…、銀月庇的月光）");
        for (final SkillDefinition definition : config.all()) {
            assertTrue(SkillConfig.VISUALS.contains(definition.visual()), definition.id());
            if (definition.mechanic() == Mechanic.WARCRY) {
                // 戰吼不算法術（2026-10）：type 寫 spell（照施法時間），但沒有 spell 標籤，也不是攻擊
                assertFalse(definition.tags().contains(Tags.SPELL) || definition.tags().contains(Tags.ATTACK), definition.id());
                assertEquals("戰吼", definition.typeName(), definition.id());
            } else if (definition.summon() != null) {
                // 召喚技能不算法術（2026-10）：沒有 spell 標籤，種類寫「召喚」
                assertFalse(definition.tags().contains(Tags.SPELL) || definition.tags().contains(Tags.ATTACK), definition.id());
                assertEquals("召喚", definition.typeName(), definition.id());
            } else if (definition.mechanic() == Mechanic.AURA && !definition.tags().contains(Tags.HERALD)) {
                // 光環、常駐技能不算法術（2026-10）：type 寫 spell（照施法時間），但沒有 spell 標籤；捷光環例外
                assertFalse(definition.tags().contains(Tags.SPELL) || definition.tags().contains(Tags.ATTACK), definition.id());
                assertEquals(definition.tags().contains(Tags.PERSISTENT) ? "常駐" : "光環", definition.typeName(), definition.id());
                assertFalse(SkillService.headlineChips(definition).contains(tw.exilecore.item.AffixChips.Chip.CASTER.key()),
                        definition.id() + "：標籤列不掛「法術」");
            } else if (definition.mechanic() == Mechanic.AURA) {
                // 捷光環：輔助要傳給擊殺效果，所以內部還留著 spell 標籤；顯示的種類是「捷光環」，不寫法術
                assertEquals("捷光環", definition.typeName(), definition.id());
                assertFalse(SkillService.headlineChips(definition).contains(tw.exilecore.item.AffixChips.Chip.CASTER.key()),
                        definition.id() + "：標籤列不掛「法術」");
            } else if (definition.mechanic() == Mechanic.MARK && !definition.params().mark().curse()) {
                // 2026-10-26 照 PoE2：印記不算法術（詛咒才是），種類寫「印記」
                assertFalse(definition.tags().contains(Tags.SPELL) || definition.tags().contains(Tags.ATTACK), definition.id());
                assertEquals("印記", definition.typeName(), definition.id());
            } else if (!definition.isAttack() && definition.tags().contains(Tags.BUFF)) {
                // 2026-10-28 照 PoE2：增益技能（急裝）不算法術，種類寫「增益」
                // 2026-10-29：原作同時是增益、法術的（雷縛長棍、霜鏃…，yml 自己寫 spell）照樣算法術，這裡不再檢查沒有 spell
                assertEquals("增益", definition.typeName(), definition.id());
            } else {
                assertTrue(definition.tags().contains(definition.isAttack() ? Tags.ATTACK : Tags.SPELL), definition.id());
                assertEquals(definition.isAttack() ? "攻擊" : "法術", definition.typeName(), definition.id());
            }
            if (definition.hidden()) {
                assertEquals(0, definition.mana().last(), definition.id() + " 普攻、捷的效果不耗魔力");
            } else if (definition.mechanic() == Mechanic.AURA || definition.reservesSpiritOnly()) {
                // 2026-10-28 照 PoE2：保留精魂的主動技能（掠閃）也不花魔力
                assertEquals(0, definition.mana().last(), definition.id() + " 光環用保留的，不耗魔力");
                assertFalse(definition.description().isBlank(), definition.id() + " 要有說明");
            } else if (definition.id().endsWith("_banner") || config.mount(definition.id()) != null || definition.id().equals("parry")) {
                // 2026-10：旗幟（靠冷卻）與坐騎不耗魔力；2026-10-24：招架照 PoE2 不花魔力
                assertEquals(0, definition.mana().last(), definition.id() + " 旗幟、坐騎不耗魔力");
                assertFalse(definition.description().isBlank(), definition.id() + " 要有說明");
            } else if ("weapon".equals(definition.source())) {
                // 2026-10-26：法杖、長杖、權杖賦予的技能照 PoE2 不花魔力
                assertEquals(0, definition.mana().last(), definition.id() + " 武器賦予的不耗魔力");
                assertFalse(definition.description().isBlank(), definition.id() + " 要有說明");
            } else if ("ascendancy".equals(definition.source())) {
                // 2026-10-26 照 PoE2：昇華天賦賦予的技能，有的不花魔力（指令、砲手、要榮耀的），有的照原作花魔力
                assertTrue(definition.mana().first() >= 0, definition.id() + " 魔力不能是負的");
                assertFalse(definition.description().isBlank(), definition.id() + " 要有說明");
            } else if (POE2_FREE.contains(definition.id())) {
                // 2026-10-26：照 PoE2 不花魔力（熊爪、狼爪、飛龍的翼刃斬、烈陽珠）
                assertEquals(0, definition.mana().last(), definition.id() + " 照 PoE2 不耗魔力");
            } else {
                assertTrue(definition.mana().first() > 0, definition.id() + " 要耗魔力");
                assertFalse(definition.description().isBlank(), definition.id() + " 要有說明");
                assertNotNull(definition.icon());
            }
            if (definition.isAttack() && definition.weaponless()) {
                // 2026-10-27 照 PoE2：不用武器的攻擊（血爆、嗅血爆、混合物）寫自己的基礎傷害；這次之前攻擊一律不能寫 damage
                assertEquals(1.0, definition.damageTypes().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9, definition.id());
            } else if (definition.isAttack()) {
                assertNull(definition.damage(), definition.id());
            } else if (definition.dealsDamage()) {
                assertNotNull(definition.damage(), definition.id());
                assertEquals(1.0, definition.damageTypes().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
            } else {
                assertNull(definition.damage(), definition.id() + " 不造成傷害的法術（位移、印記）不寫 damage");
            }
        }
        // 弓的技能都用原版箭矢、需要弓；法術什麼武器都能放
        for (final String id : List.of("lightning_arrow", "ice_shot")) {
            assertTrue(config.skill(id).params().arrow(), id);
            assertEquals(Set.of(Tags.BOW), config.skill(id).weapons(), id);
        }
        for (final String id : List.of("fireball", "frost_nova", "spark", "arc", "essence_drain", "contagion")) {
            assertTrue(config.skill(id).weapons().isEmpty(), id);
        }
        // 新武器的技能：弩用原版箭矢；長棍、矛的只有那種武器能用
        for (final String id : List.of("explosive_bolt", "permafrost_bolts")) {
            assertTrue(config.skill(id).params().arrow(), id);
            assertEquals(Set.of(Tags.CROSSBOW), config.skill(id).weapons(), id);
        }
        assertEquals(Set.of(Tags.QUARTERSTAFF), config.skill("tempest_flurry").weapons());
        assertEquals(Set.of(Tags.QUARTERSTAFF), config.skill("ice_strike").weapons());
        assertEquals(Set.of(Tags.SPEAR), config.skill("lightning_spear").weapons());
        assertEquals(Set.of(Tags.SPEAR), config.skill("whirling_slash").weapons());
        assertTrue(config.skill("explosive_bolt").params().explode().first() > 0, "爆裂弩箭會爆炸");
        assertEquals(java.util.Map.of(tw.exilecore.stats.DamageType.CHAOS, 1.0), config.skill("essence_drain").damageTypes());
    }

    @Test
    void thrownSpearIsAProjectileNotMelee() {
        final Set<String> spear = Set.of("weapon", "one_hand", "spear", "melee");
        final tw.exilecore.stats.SkillProfile thrown = SkillProfiles.build(config, config.skill("lightning_spear"), 1, spear);
        assertTrue(thrown.tags().contains(Tags.PROJECTILE));
        assertFalse(thrown.tags().contains(Tags.MELEE), "擲出去的矛不吃近戰的加成");
        assertTrue(thrown.tags().contains(Tags.SPEAR), "武器的標籤照樣有：增加矛傷害吃得到");
        final tw.exilecore.stats.SkillProfile whirl = SkillProfiles.build(config, config.skill("whirling_slash"), 1, spear);
        assertTrue(whirl.tags().contains(Tags.MELEE), "旋矛擊是近戰");
    }

    @Test
    void everyClassHasAStarterSkill() {
        assertEquals("boneshatter", config.starter(CharacterClass.MARAUDER.id()).id());
        assertEquals("explosive_bolt", config.starter(CharacterClass.MERCENARY.id()).id());
        assertEquals("lightning_spear", config.starter(CharacterClass.HUNTRESS.id()).id());
        assertEquals("lightning_arrow", config.starter(CharacterClass.RANGER.id()).id());
        assertEquals("tempest_flurry", config.starter(CharacterClass.MONK.id()).id());
        assertEquals("fireball", config.starter(CharacterClass.SORCERESS.id()).id());
        assertEquals("essence_drain", config.starter(CharacterClass.WITCH.id()).id());
        for (final CharacterClass characterClass : CharacterClass.values()) {
            assertNotNull(config.starter(characterClass.id()), characterClass + " 沒有起始技能");
        }
        assertNull(config.starter("nobody"));
        // 起始技能要能用職業的起始武器（2026-10：重甲鬥士的新手武器是錘）
        assertTrue(config.starter("marauder").usableWith(Set.of("weapon", "one_hand", Tags.MACE, Tags.MELEE)));
    }

    @Test
    void defaultAttackFollowsTheWeapon() throws IOException {
        final ItemRepository items = new ItemRepository();
        items.load(DATA);
        int ranged = 0;
        for (final ItemBase base : items.bases()) {
            if (!base.isWeapon()) {
                continue;
            }
            assertTrue(base.hasTag(Tags.MELEE) ^ base.hasTag(Tags.RANGED), base.id() + " 要是近戰或遠程其中一種");
            final String expected;
            if (base.hasTag(Tags.BOW)) {
                expected = SkillConfig.DEFAULT_BOW;
            } else if (base.hasTag(Tags.CROSSBOW)) {
                expected = SkillConfig.DEFAULT_CROSSBOW;
            } else if (base.hasTag(Tags.STAFF)) {
                expected = SkillConfig.DEFAULT_STAFF;
            } else if (base.hasTag(Tags.WAND)) {
                expected = SkillConfig.DEFAULT_WAND;
            } else if (base.hasTag(Tags.QUARTERSTAFF)) {
                expected = SkillConfig.DEFAULT_QUARTERSTAFF;
            } else if (base.hasTag(Tags.SPEAR)) {
                expected = SkillConfig.DEFAULT_SPEAR;
            } else {
                expected = SkillConfig.DEFAULT_MELEE;
            }
            assertEquals(expected, config.defaultAttack(base.tags()).id(), base.id());
            if (base.hasTag(Tags.RANGED)) {
                ranged++;
                assertTrue(SkillProfile.defaultAttack(base.tags()).tags().contains(Tags.PROJECTILE), base.id());
            }
        }
        assertEquals(items.bases().stream().filter(ItemBase::isWeapon)
                .filter(base -> base.hasTag(Tags.BOW) || base.hasTag(Tags.CROSSBOW) || base.hasTag(Tags.WAND) || base.hasTag(Tags.STAFF))
                .count(), ranged, "遠程只有弓、弩、法杖、長杖");
        assertEquals(16 - 2 + 14 + 17, ranged, "弓、弩、法杖、長杖（2026-10 各分好幾階；2026-10-26 法杖 13、長杖 14 把照 PoE2）");
        // 2026-10-26 照 PoE2：長棍普攻也只打一個（Quarterstaff Strike）；矛刺得比較遠
        assertEquals(1, config.skill(SkillConfig.DEFAULT_QUARTERSTAFF).params().targets());
        assertTrue(config.skill(SkillConfig.DEFAULT_SPEAR).params().reach() > config.skill(SkillConfig.DEFAULT_MELEE).params().reach());
        assertEquals(SkillConfig.DEFAULT_MELEE, config.defaultAttack(Set.of()).id(), "徒手");
    }

    @Test
    void levelRequirementsAndGrowth() {
        assertEquals(1, config.requiredLevel(1));
        // 2026-10-26：需求等級照 PoE2（18 級 78、19 級 84、20 級 90）；掉落照原本的曲線
        assertEquals(78, config.requiredLevel(18));
        assertEquals(84, config.requiredLevel(19));
        assertEquals(90, config.requiredLevel(20));
        assertEquals(1, config.maxLevelFor(1));
        assertEquals(4, config.maxLevelFor(10));
        assertEquals(14, config.maxLevelFor(60));
        assertEquals(18, config.maxLevelFor(79));
        assertEquals(64, config.dropLevel(19));
        assertEquals(80, config.dropLevel(20));
        assertEquals(20, config.maxLevelFor(100));
        assertEquals(19, config.uncutLevelFor(70), "T8 地圖掉 19 級的未切割寶石");
        assertEquals(20, config.uncutLevelFor(84), "T14 以上的地圖才掉 20 級");
        assertEquals(1.05, config.spellGrowth(1), 1e-9);
        assertEquals(1.05 * Math.pow(1.13 / 1.05, 4 / 9.0), config.spellGrowth(5), 1e-9);   // 等比內插
        assertEquals(7.01, config.spellGrowth(64), 1e-9);
        assertEquals(9.23, config.spellGrowth(80), 1e-9);
        assertEquals(9.23, config.spellGrowth(100), 1e-9, "超過最後一個點取端點");
    }

    @Test
    void profilesScaleWithLevel() {
        final SkillDefinition fireball = config.skill("fireball");
        final SkillProfile level1 = SkillProfiles.build(config, fireball, 1, Set.of(Tags.SWORD));
        // 2026-10-26：熾星彈改成照 PoE2 的逐級表（每一級的實際傷害，不乘成長倍率）：1 級 8–12、20 級 224–336
        assertEquals(8, level1.spellDamage().get(DamageType.FIRE), 1e-9);
        assertEquals(12, level1.spellMax().get(DamageType.FIRE), 1e-9);
        assertFalse(level1.tags().contains(Tags.SWORD), "法術不帶武器標籤");
        final SkillProfile level20 = SkillProfiles.build(config, fireball, 20, Set.of());
        assertEquals(224, level20.spellDamage().get(DamageType.FIRE), 1e-9, "20 級照 PoE2");
        assertEquals(336, level20.spellMax().get(DamageType.FIRE), 1e-9);
        // 2026-10-27 照站長：魔力消耗再乘 skills.yml 的 mana_cost_scale（0.5）；表上是原作的 10、104
        assertEquals(Math.round(10 * SkillConfig.manaCostScale()), SkillProfiles.manaCost(fireball, 1));
        assertEquals(Math.round(104 * SkillConfig.manaCostScale()), SkillProfiles.manaCost(fireball, 20));

        final SkillDefinition split = config.skill("permafrost_bolts");
        // 2026-10-26：照 PoE2（Permafrost Bolts 一次 10 發碎片，疊在同一隻上）
        assertEquals(10, split.params().projectiles().intAt(1));
        assertEquals(10, split.params().projectiles().intAt(20));
        assertTrue(split.tags().contains("merging"));
        final SkillProfile arrows = SkillProfiles.build(config, split, 1, Set.of("weapon", "two_hand", Tags.CROSSBOW, Tags.RANGED));
        assertTrue(arrows.tags().containsAll(Set.of(Tags.ATTACK, Tags.PROJECTILE, Tags.CROSSBOW)), "攻擊帶武器標籤");
        assertEquals(0.17, arrows.effectiveness(), 1e-9); // 2026-10-26：照 PoE2（Permafrost Bolts 1 級 17%）

        final SkillProfile burning = SkillProfiles.build(config, config.skill("explosive_bolt"), 1, Set.of(Tags.CROSSBOW));
        assertEquals(0.8, burning.conversion().get(DamageType.FIRE), 1e-9); // 2026-10-26：照 PoE2 Explosive Shot 80% 轉火焰
    }

    @Test
    void intervalsUseAttackAndCastSpeed() {
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.flat(StatKeys.WEAPON_APS, 1.5, "武器"));
        final SkillDefinition heavy = config.skill("boneshatter");
        final SkillProfile heavyProfile = SkillProfiles.build(config, heavy, 1, Set.of(Tags.MACE));
        assertEquals(20 / (1.5 * 0.6), SkillProfiles.intervalTicks(sheet, heavy, heavyProfile), 1e-9);
        final SkillDefinition leap = config.skill("leap_slam");
        assertEquals(20 / (1.5 * 1.0), SkillProfiles.intervalTicks(sheet, leap, SkillProfiles.build(config, leap, 1, Set.of())), 1e-9);

        final SkillDefinition fireball = config.skill("fireball");
        final SkillProfile fire = SkillProfiles.build(config, fireball, 1, Set.of());
        assertEquals(24, SkillProfiles.intervalTicks(sheet, fireball, fire), 1e-9, "施法時間 1.2 秒");
        sheet.add(new Modifier(StatKeys.CAST_SPEED, ModType.INCREASED, 0.5, Set.of(Tags.SPELL), "施法速度"));
        assertEquals(16, SkillProfiles.intervalTicks(sheet, fireball, fire), 1e-9);
        assertEquals(20 / (1.5 * 0.6), SkillProfiles.intervalTicks(sheet, heavy, heavyProfile), 1e-9, "施法速度不影響攻擊");

        // 面板上的「平均每次」：2026-10-26 照 PoE2 逐級表，熾星彈 1 級 8–12 平均 10（不再乘成長倍率）
        final DamagePacket expected = DamageCalculator.expectedOutgoing(new StatSheet(), fire);
        assertEquals(10, expected.get(DamageType.FIRE), 1e-9);
    }

    @Test
    void badFilesAreRejectedWithTheFieldName(@TempDir final Path dir) throws IOException {
        final String shipped = Files.readString(DATA.resolve(SkillConfig.FILE), StandardCharsets.UTF_8);
        assertRejected(dir, shipped.replace("visual: fireball", "visual: lasers"), "visual");
        // 2026-10-26：星屑連矢照 PoE2 改成矢幕（增益），拿撼山爪的攻擊速度那一行來加 damage
        assertRejected(dir, shipped.replace("    attack_speed: 1   # 2026-10-26 照 PoE2（poe2db Maul Attack Speed", "    damage: [1, 2]\n    attack_speed: 1   # 2026-10-26 照 PoE2（poe2db Maul Attack Speed"), "damage");
        assertRejected(dir, shipped.replace("  default_staff:", "  default_staff_renamed:"), "default_staff");
        assertRejected(dir, shipped.replace("starter: witch", "starter: marauder"), "起始技能");
        // 2026-10-26：衝霄弩台照 PoE2 改成 60% 轉火焰，資料裡沒有 { fire: 0.5 } 了，改拿 { fire: 0.6 } 來弄壞
        assertRejected(dir, shipped.replace("convert: { fire: 0.6 }", "convert: { fire: 0.8, cold: 0.5 }"), "convert");
        assertRejected(dir, shipped.replace("speed: 0.55, range: 12", "speed: 9, range: 12"), "speed"); // 2026-10-26：熾星彈射程照 PoE2 改 12
    }

    @Test
    void sameSupportCanGoOnDifferentSkillsUnlessTurnedOff(@TempDir final Path dir) throws IOException {
        assertFalse(config.supportOncePerCharacter(), "預設：不同技能可以各掛一顆同一種輔助");
        Files.writeString(dir.resolve(SkillConfig.FILE), Files.readString(DATA.resolve(SkillConfig.FILE), StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        final String supports = Files.readString(DATA.resolve(SkillConfig.SUPPORTS_FILE), StandardCharsets.UTF_8);
        assertTrue(supports.contains("once_per_character: false"));
        Files.writeString(dir.resolve(SkillConfig.SUPPORTS_FILE), supports.replace("once_per_character: false", "once_per_character: true"),
                StandardCharsets.UTF_8);
        final SkillConfig poe2 = new SkillConfig();
        poe2.load(dir);
        assertTrue(poe2.supportOncePerCharacter(), "改成 true：PoE2 的規則");
    }

    private static void assertRejected(final Path dir, final String content, final String mention) throws IOException {
        Files.writeString(dir.resolve(SkillConfig.FILE), content, StandardCharsets.UTF_8);
        final SkillConfig broken = new SkillConfig();
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> broken.load(dir));
        assertTrue(error.getMessage().contains(mention), error.getMessage());
    }

    @Test
    void rangedMinionsAreTaggedRangedNotProjectile() {
        // 2026-10：遠程召喚物（骸骨射手、術士）標「遠程」：「+x 投射物技能等級」吃不到
        // 2026-10-29 照 PoE2 的 Minion Types：射手、焰術士、霜術士的召喚物技能有 Projectile，穿透、分光照那個掛得上、效果給召喚物的彈道
        //（MinionSupportTypesTest）；雷術士的電弧沒有 Projectile 照舊掛不上。這次之前四個都掛不上
        for (final String id : List.of("skeletal_archers", "skeletal_pyromancer", "skeletal_stormcaller", "skeletal_frostcaller")) {
            final SkillDefinition summon = config.skill(id);
            assertTrue(summon.tags().contains(Tags.RANGED), id);
            assertFalse(summon.tags().contains(Tags.PROJECTILE), id);
            final boolean shoots = !"skeletal_stormcaller".equals(id);
            assertEquals(shoots, config.support("pierce").supports(summon), id + "：穿透");
            assertEquals(shoots, config.support("multiple_projectiles").supports(summon), id + "：分光");
        }
        assertTrue(config.skill("summon_skeletons").tags().contains(Tags.MELEE), "近戰的召喚物照舊");
    }
}
