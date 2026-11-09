package tw.exilecore.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.WeaponSets;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.Rarity;
import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;
import tw.exilecore.passive.TreeCode;
import tw.exilecore.skill.SkillConfig;
import tw.exilecore.skill.SkillDefinition;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-09 照站長「網頁天賦規劃器和網頁 DPS 計算器也補上兩組」：計算器讀兩組武器、兩組的武器組天賦、技能綁定；舊格式照常讀；
 * 換成另一組的角色（綁另一組的技能用這個算）。
 */
class BuildCalcWeaponSetsTest {

    static PassiveTree tree;
    static SkillConfig skills;

    @BeforeAll
    static void load() throws Exception {
        tree = PassiveTree.load(BuildCalcTest.DATA.resolve("passives/tree.json"));
        skills = new SkillConfig();
        skills.load(BuildCalcTest.DATA);
        BuildCalcTest.tree = tree;
    }

    private static List<String> walk(final int count) {
        return new ArrayList<>(BuildCalcTest.walk("witch", count));
    }

    private static JsonArray array(final String... ids) {
        final JsonArray a = new JsonArray();
        for (final String id : ids) {
            a.add(id);
        }
        return a;
    }

    @Test
    void readsBothWeaponSetsAndBindings() throws Exception {
        final List<String> path = walk(8);
        // 前 6 點一般；第 7 點是第 I 組（正在用）的武器組天賦；第 8 點是第 II 組的
        final Set<String> common = new LinkedHashSet<>(path.subList(0, 6));
        final String mine = path.get(6), theirs = path.get(7);
        final Set<String> passives = new LinkedHashSet<>(common);
        passives.add(mine);
        final SkillDefinition skill = skills.all().stream().filter(d -> !d.hidden() && d.dealsDamage()).findFirst().orElseThrow();
        final JsonObject json = new JsonObject();
        json.addProperty("tree", TreeCode.encode(tree, "witch", passives));
        json.addProperty("level", 70);
        json.addProperty("weaponSet", 1);
        final JsonObject ws = new JsonObject();
        ws.add("1", array(mine, "不存在的節點", tree.start("witch").id()));
        ws.add("2", array(theirs, path.get(0))); // path.get(0) 已經是一般節點：當一般節點
        json.add("wsNodes", ws);
        final JsonArray spare = new JsonArray();
        spare.add(com.google.gson.JsonParser.parseString(new com.google.gson.Gson().toJson(new ItemData("wand_01", Rarity.NORMAL, 10))));
        spare.add(com.google.gson.JsonNull.INSTANCE);
        json.add("spare", spare);
        final JsonArray skillArray = new JsonArray();
        final JsonObject s = new JsonObject();
        s.addProperty("id", skill.id());
        s.addProperty("ws", 2);
        skillArray.add(s);
        json.add("skills", skillArray);
        final JsonObject progress = new JsonObject();
        progress.addProperty("wsnodes.1", "舊的快照會被換掉");
        json.add("progress", progress);

        final BuildCalc.Build build = BuildCalc.parse(json.toString(), tree, skills);
        assertEquals("1", build.progress().get(PlayerState.WEAPON_SET_KEY));
        assertEquals(Set.of(mine), WeaponSets.nodes(build.progress(), 1), "不認得的、起點拿掉；舊快照的換掉");
        assertEquals(Set.of(theirs), WeaponSets.nodes(build.progress(), 2), "已經是一般節點的不算武器組天賦");
        assertEquals(2, WeaponSets.binding(build.progress(), skill.id()));
        assertTrue(BuildCalc.boundToOther(build, skill.id()));
        assertEquals(PlayerState.GEAR_SLOTS.length + 2, build.gear().size(), "另一組武器接在後面");
        assertNotNull(build.gear().get(BuildCalc.SPARE_MAIN));

        final BuildCalc.Build other = BuildCalc.otherWeaponSet(build);
        assertNotNull(other);
        assertEquals("2", other.progress().get(PlayerState.WEAPON_SET_KEY));
        final Set<String> expected = new LinkedHashSet<>(common);
        expected.add(theirs);
        assertEquals(expected, other.passives(), "第 I 組的武器組天賦拿掉、第 II 組的加上");
        assertEquals("wand_01", other.gear().get(4).base(), "武器換成另一組");
        assertNull(other.gear().get(BuildCalc.SPARE_MAIN), "原本那組（沒有武器）收到後面");
        assertFalse(BuildCalc.boundToOther(other, skill.id()), "換過去之後就是正在用的那一組");
    }

    @Test
    void oldRequestsStillWork() throws Exception {
        final List<String> path = walk(5);
        final JsonObject json = new JsonObject();
        json.addProperty("tree", TreeCode.encode(tree, "witch", new LinkedHashSet<>(path)));
        json.addProperty("level", 50);
        final BuildCalc.Build build = BuildCalc.parse(json.toString(), tree, skills);
        assertEquals(PlayerState.GEAR_SLOTS.length, build.gear().size(), "沒有 spare：照舊 11 格");
        assertNull(build.progress().get(PlayerState.WEAPON_SET_KEY));
        assertNull(BuildCalc.otherWeaponSet(build), "沒有另一組武器也沒有武器組天賦：不用另外算");
    }

    @Test
    void snapshotProgressCarriesWeaponSets() {
        assertTrue(BuildCalc.allowedProgress("wsnodes.1"));
        assertTrue(BuildCalc.allowedProgress("skillws.frost_drill"));
        assertTrue(BuildCalc.allowedProgress(PlayerState.WEAPON_SET_KEY));
        final PassiveNode start = tree.start("witch");
        assertTrue(start.isStart());
    }
}
