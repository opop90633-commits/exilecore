package tw.exilecore.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import tw.exilecore.skill.SkillConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-09 照站長「規劃器裡幻影神射、幻弓影沒辦法放技能」：宿主寶石帶「孔裡能放的技能」清單。 */
class PlannerHostSkillsTest {

    @Test
    void mirageHostsListRangedAttacks() throws Exception {
        final SkillConfig config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        final JsonObject root = JsonParser.parseString(new String(BuildCalc.skillsJson(config), StandardCharsets.UTF_8)).getAsJsonObject();
        for (final String host : new String[]{"mirage_deadeye", "phantom_bow_shade"}) {
            JsonObject found = null;
            for (final JsonElement e : root.getAsJsonArray("skills")) {
                if (e.getAsJsonObject().get("id").getAsString().equals(host)) {
                    found = e.getAsJsonObject();
                }
            }
            assertTrue(found != null && found.has("inner") && found.getAsJsonArray("inner").size() > 3, host + " 孔裡要能放遠程投射物攻擊");
            assertTrue(found.getAsJsonArray("inner").toString().contains("frost_drill") == false, host + "：法術不能放");
        }
    }
}
