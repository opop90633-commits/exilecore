package tw.exilecore.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.ascendancy.AscendancyConfig;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.currency.CurrencyRepository;
import tw.exilecore.event.EventConfig;
import tw.exilecore.flask.FlaskConfig;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.map.AtlasTree;
import tw.exilecore.map.MapConfig;
import tw.exilecore.map.MapEventConfig;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;
import tw.exilecore.skill.SkillConfig;
import tw.exilecore.skill.SkillDefinition;
import tw.exilecore.skill.SkillProfiles;
import tw.exilecore.worldevent.WorldEventConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-26：網頁圖鑑的完整數值（{@link CodexValues}）——每一類都有輸出、數量跟設定檔一致、數值是從設定物件算的（不是寫死的）。
 */
class CodexValuesTest {

    private static final Path DATA = Path.of("src/main/resources/data");
    private static SkillConfig skills;
    private static ItemRepository items;
    private static CurrencyRepository currencies;
    private static FlaskConfig flasks;
    private static AscendancyConfig ascendancy;
    private static PassiveTree tree;
    private static MonsterConfig monsters;
    private static MapConfig maps;
    private static MapEventConfig mapEvents;
    private static EventConfig events;
    private static WorldEventConfig worldEvents;
    private static AtlasTree atlas;
    private static JsonObject root;

    @BeforeAll
    static void load() throws Exception {
        skills = new SkillConfig();
        skills.load(DATA);
        items = new ItemRepository();
        items.load(DATA);
        currencies = new CurrencyRepository();
        currencies.load(DATA);
        flasks = new FlaskConfig();
        flasks.load(DATA);
        ascendancy = new AscendancyConfig();
        ascendancy.load(DATA);
        tree = PassiveTree.load(DATA.resolve("passives/tree.json"));
        monsters = new MonsterConfig();
        monsters.load(DATA);
        maps = new MapConfig();
        maps.load(DATA);
        mapEvents = new MapEventConfig();
        mapEvents.load(DATA);
        events = new EventConfig();
        events.load(DATA);
        worldEvents = new WorldEventConfig();
        worldEvents.load(DATA);
        atlas = new AtlasTree();
        atlas.load(DATA);
        root = CodexValues.build(new CodexValues.Sources(skills, items, currencies, flasks, null, ascendancy, tree, monsters, maps,
                mapEvents, events, worldEvents, atlas, source -> "來源：" + source));
        final String dump = System.getenv("CODEX_DUMP");
        if (dump != null) {
            Files.writeString(Path.of(dump), root.toString());
        }
    }

    @Test
    void everyCategoryIsBuiltWithoutErrors() {
        assertEquals(0, root.getAsJsonArray("errors").size(), root.getAsJsonArray("errors").toString());
        for (final String key : new String[] {"skills", "supports", "uniques", "bases", "affixes", "currencies", "runes", "ascendancies"}) {
            assertFalse(root.getAsJsonArray(key).isEmpty(), key + " 是空的");
        }
        assertFalse(root.getAsJsonObject("passives").getAsJsonArray("notables").isEmpty());
        assertFalse(root.getAsJsonObject("atlas").getAsJsonArray("nodes").isEmpty());
        assertFalse(root.getAsJsonObject("monsters").getAsJsonArray("types").isEmpty());
        assertFalse(root.getAsJsonObject("monsters").getAsJsonArray("mods").isEmpty());
        assertFalse(root.getAsJsonObject("maps").getAsJsonArray("maps").isEmpty());
        assertFalse(root.getAsJsonObject("maps").getAsJsonArray("finals").isEmpty());
        assertFalse(root.getAsJsonObject("events").getAsJsonArray("map").isEmpty());
        assertFalse(root.getAsJsonObject("events").getAsJsonArray("monthly").isEmpty());
    }

    @Test
    void skillCountsAndLevelTablesComeFromTheConfig() {
        final List<SkillDefinition> listed = skills.all().stream().filter(CodexValues::listedSkill).toList();
        final JsonArray json = root.getAsJsonArray("skills");
        assertEquals(listed.size(), json.size(), "技能（含賦予技能）數量要跟 skills.yml 一樣（普攻、proc_ 不列）");
        // 法杖、長杖、權杖賦予的技能也要在（以前圖鑑沒有分出來）
        final Set<String> granted = new HashSet<>(items.grantedSkills().values());
        int grantedListed = 0;
        for (final JsonElement each : json) {
            final JsonObject skill = each.getAsJsonObject();
            final SkillDefinition definition = skills.skill(skill.get("id").getAsString());
            final JsonArray levels = skill.getAsJsonArray("levels");
            assertEquals(CodexValues.MAX_LEVEL, levels.size(), definition.id() + " 要有 1～40 級");
            if (skill.get("granted").getAsBoolean()) {
                grantedListed++;
            }
            // 數值跟遊戲算的一樣（改 skills.yml 就跟著變）
            final JsonObject lv20 = levels.get(19).getAsJsonObject();
            assertEquals(20, lv20.get("lv").getAsInt());
            assertEquals(skills.requiredLevel(20), lv20.get("req").getAsInt());
            if (lv20.has("min")) {
                final double[] range = SkillProfiles.spellDamage(skills, definition, 20);
                assertEquals(Math.round(range[0]), lv20.get("min").getAsLong(), definition.id());
                assertEquals(Math.round(range[1]), lv20.get("max").getAsLong(), definition.id());
            }
            final JsonArray lines = skill.getAsJsonArray("lines");
            for (final JsonElement row : levels) {
                if (row.getAsJsonObject().has("l")) {
                    for (final JsonElement id : row.getAsJsonObject().getAsJsonArray("l")) {
                        assertTrue(id.getAsInt() < lines.size(), definition.id());
                    }
                }
            }
        }
        assertTrue(grantedListed >= granted.stream().filter(id -> skills.skill(id) != null && CodexValues.listedSkill(skills.skill(id))).count(),
                "武器賦予的技能都要標出來");
    }

    @Test
    void supportsUniquesCurrenciesAndRunesMatchTheirFiles() throws Exception {
        final long supports = skills.supports().stream().filter(s -> s.name() != null && !s.name().isBlank()).count();
        assertEquals(supports, root.getAsJsonArray("supports").size());
        for (final JsonElement each : root.getAsJsonArray("supports")) {
            assertTrue(each.getAsJsonObject().has("mana"));
            assertTrue(each.getAsJsonObject().has("lines"));
        }
        // 傳奇：uniques.yml 沒有退役的每一件都要列（以前珠寶、藥劑、護符以外的靠規劃器）
        final YamlNode uniques = YamlNode.load(DATA.resolve("uniques.yml"));
        int active = 0;
        for (final String id : uniques.keys()) {
            if (!uniques.node(id).bool("retired", false)) {
                active++;
            }
        }
        assertEquals(active, root.getAsJsonArray("uniques").size());
        for (final JsonElement each : root.getAsJsonArray("uniques")) {
            final JsonObject unique = each.getAsJsonObject();
            assertFalse(unique.getAsJsonArray("mods").isEmpty(), unique.get("id").getAsString());
        }
        assertEquals(currencies.size(), root.getAsJsonArray("currencies").size());
        assertEquals(YamlNode.load(DATA.resolve("runes.yml")).node("runes").keys().size(), root.getAsJsonArray("runes").size());
    }

    @Test
    void basesIncludeEveryActiveBaseFlaskAndCharm() throws Exception {
        int active = 0;
        for (final String file : new String[] {"weapons", "weapons_casters", "armour", "shields", "quivers", "jewellery", "jewels", "maps"}) {
            final YamlNode node = YamlNode.load(DATA.resolve("bases/" + file + ".yml"));
            for (final String id : node.keys()) {
                if (!node.node(id).bool("retired", false)) {
                    active++;
                }
            }
        }
        final YamlNode flaskFile = YamlNode.load(DATA.resolve("flasks.yml"));
        // 2026-10-26：照 PoE2 拿掉的藥劑（熔星、星骸 4 種）與護符（續命護符）標 retired: true，圖鑑不列（這次之前全部都算）
        for (final YamlNode section : List.of(flaskFile.node("bases"), flaskFile.node("charms").node("bases"))) {
            for (final String id : section.keys()) {
                if (!section.node(id).bool("retired", false)) {
                    active++;
                }
            }
        }
        final JsonArray bases = root.getAsJsonArray("bases");
        assertEquals(active, bases.size(), "基底（武器、法杖類、護甲、盾、箭袋、飾品、珠寶、換界石、藥劑、護符）");
        final Set<String> groups = new HashSet<>();
        for (final JsonElement each : bases) {
            final JsonObject base = each.getAsJsonObject();
            groups.add(base.get("group").getAsString());
            final ItemBase def = items.base(base.get("id").getAsString());
            if (def.isWeapon() && !def.damage().isEmpty()) {
                assertTrue(base.has("damage") && base.has("aps") && base.has("crit"), def.id());
            }
            if (def.isArmour() && def.armour() > 0) {
                assertTrue(base.get("armour").getAsLong() > 0, def.id());
            }
            if (def.isFlask()) {
                assertTrue(base.has("recovery"), def.id());
            }
        }
        for (final String group : new String[] {"weapon", "caster", "armour", "shield", "quiver", "jewellery", "jewel", "map", "flask", "charm"}) {
            assertTrue(groups.contains(group), "基底要有 " + group + "：" + groups);
        }
    }

    @Test
    void affixesCoverEveryKindWithTiersAndWeights() {
        final Set<String> categories = new HashSet<>();
        for (final JsonElement each : root.getAsJsonArray("affixes")) {
            final JsonObject affix = each.getAsJsonObject();
            categories.add(affix.get("cat").getAsString());
            final JsonArray tiers = affix.getAsJsonArray("tiers");
            assertFalse(tiers.isEmpty());
            for (final JsonElement tier : tiers) {
                assertTrue(tier.getAsJsonObject().has("weight") && tier.getAsJsonObject().has("ilvl"));
            }
            assertFalse(affix.get("id").getAsString().startsWith("unique."), "傳奇專屬的詞綴不列在詞綴裡");
        }
        for (final String category : new String[] {"prefix", "suffix", "jewel", "flask", "map", "corrupted", "unveiled"}) {
            assertTrue(categories.contains(category), "詞綴要有 " + category + "：" + categories);
        }
    }

    @Test
    void ascendancyPassivesAtlasMonstersMapsAndEventsMatchTheirConfigs() {
        final JsonArray asc = root.getAsJsonArray("ascendancies");
        assertEquals(ascendancy.ascendancies().size(), asc.size());
        int i = 0;
        for (final AscendancyConfig.Ascendancy each : ascendancy.ascendancies()) {
            assertEquals(each.nodes().size(), asc.get(i++).getAsJsonObject().get("nodeCount").getAsInt(), each.id());
        }
        final JsonObject passives = root.getAsJsonObject("passives");
        assertEquals(tree.size(), passives.get("total").getAsInt());
        assertEquals(tree.nodes().stream().filter(n -> n.type() == PassiveNode.Type.NOTABLE).count(), passives.getAsJsonArray("notables").size());
        assertEquals(tree.nodes().stream().filter(n -> n.type() == PassiveNode.Type.KEYSTONE).count(), passives.getAsJsonArray("keystones").size());
        int small = 0;
        for (final JsonElement each : passives.getAsJsonArray("small")) {
            small += each.getAsJsonObject().get("count").getAsInt();
        }
        assertEquals(tree.nodes().stream().filter(n -> n.type() == PassiveNode.Type.SMALL).count(), small);
        assertEquals(atlas.nodes().size(), root.getAsJsonObject("atlas").getAsJsonArray("nodes").size());
        final JsonObject monsterJson = root.getAsJsonObject("monsters");
        assertEquals(monsters.types().size(), monsterJson.getAsJsonArray("types").size());
        assertEquals(monsters.mods().size(), monsterJson.getAsJsonArray("mods").size());
        final JsonObject mapJson = root.getAsJsonObject("maps");
        assertEquals(maps.maps().size(), mapJson.getAsJsonArray("maps").size());
        assertEquals(maps.finals().size(), mapJson.getAsJsonArray("finals").size());
        assertEquals(maps.tiers().levels().size(), mapJson.getAsJsonArray("tiers").size());
        final JsonObject eventJson = root.getAsJsonObject("events");
        assertEquals(mapEvents.shrines().size(), eventJson.getAsJsonArray("shrines").size());
        assertEquals(mapEvents.boxes().size(), eventJson.getAsJsonArray("boxes").size());
        assertEquals(events.themes().size(), eventJson.getAsJsonArray("monthly").size());
    }

    @Test
    void codexPageGetsAValuesLink() {
        final String page = new String(CodexValues.injectLink("<html><body><p>x</p></body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(page.contains("href=\"codex/values\"") && page.endsWith("</body></html>"));
    }
}
