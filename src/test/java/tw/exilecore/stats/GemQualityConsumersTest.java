package tw.exilecore.stats;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-28 照 PoE2（站長「技能品質的部分也全部按照原作改」）：data/gem_quality.yml 用到的每個屬性鍵（一般品質與 advanced）都要有程式在讀，
 * 不然寶石上寫了品質效果卻沒有作用。掃描方式跟 {@link StatConsumersTest} 一樣；
 * grant.＊（給自己的增益，SkillBuild.qualityGrants 拿掉前綴再給）、effect.＊（2026-10-28：加在增益本身的數值上）看後面的鍵，limit.＊（StageAttacks.limit 用名字組）看名字有沒有寫在程式裡。
 */
class GemQualityConsumersTest {

    @BeforeAll
    static void scan() throws IOException {
        if (StatConsumersTest.blob == null) {
            StatConsumersTest.scan();
        }
    }

    @SuppressWarnings("unchecked")
    static TreeSet<String> qualityStats() throws IOException {
        final TreeSet<String> stats = new TreeSet<>();
        try (Reader reader = Files.newBufferedReader(Path.of("src/main/resources/data/gem_quality.yml"), StandardCharsets.UTF_8)) {
            final Map<String, Object> root = new Yaml().load(reader);
            final List<Map<String, Object>> tables = new ArrayList<>();
            tables.add(root);
            tables.add((Map<String, Object>) root.get("advanced"));
            for (final Map<String, Object> table : tables) {
                for (final Map.Entry<String, Object> entry : table.entrySet()) {
                    if (entry.getKey().equals("advanced")) {
                        continue;
                    }
                    for (final Map<String, Object> line : (List<Map<String, Object>>) entry.getValue()) {
                        stats.add(String.valueOf(line.get("stat")));
                    }
                }
            }
        }
        return stats;
    }

    @Test
    void everyGemQualityStatHasAConsumer() throws IOException {
        final TreeSet<String> stats = qualityStats();
        assertTrue(stats.size() > 60, "讀到的鍵太少：" + stats.size());
        final List<String> missing = new ArrayList<>();
        for (final String stat : stats) {
            // 2026-10-28：effect.＊（加在增益本身的數值上，SkillBuild.withQualityEffects）也看後面的鍵；條件（@trinityhigh）拿掉
            String key = stat.startsWith(StatKeys.GRANT_PREFIX) ? stat.substring(StatKeys.GRANT_PREFIX.length())
                    : stat.startsWith(tw.exilecore.skill.SkillBuild.EFFECT_PREFIX) ? stat.substring(tw.exilecore.skill.SkillBuild.EFFECT_PREFIX.length()) : stat;
            key = Recently.stat(key);
            final boolean ok = key.startsWith("limit.") ? StatConsumersTest.written(key.substring("limit.".length()))
                    : StatConsumersTest.consumed(key);
            if (!ok) {
                missing.add(stat);
            }
        }
        assertTrue(missing.isEmpty(), "gem_quality.yml 的這些屬性鍵沒有程式在讀：" + missing);
    }
}
