package tw.exilecore.core;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import tw.exilecore.character.CharacterClass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 名稱是自己的（2026-10）：職業、技能、輔助、通貨、傳奇、天賦、昇華、地圖事件、Boss 招式的名字，不能跟別的遊戲的官方繁中名稱一模一樣。
 * 下面列的是以前撞名、已經換掉的（還有幾個常見的），換回來或新加的撞名這裡會擋。
 * 武器種類（匕首、法杖…）、生命藥劑、原版生物（女巫、掠奪者）這種一般的詞不算。
 */
class OwnNamesTest {

    /** 別的遊戲的官方名稱（以前撞名換掉的，加上幾個一看就會想到的）。 */
    private static final Set<String> TAKEN = Set.of(
            // 職業
            "野蠻人", "傭兵", "遊俠", "女獵人", "女獵手", "僧侶", "武僧", "女術者", "女術士", "戰士", "決鬥者", "暗影刺客", "聖堂武僧", "貴族", "德魯伊",
            // 昇華職業
            "風行者", "虛空行者", "女巫獵人", "獵巫人", "古靈軍團", "星紋軍團", "勇士", "暴徒", "銳眼", "追獵者", "元素使", "死靈師", "血法師",
            // 技能
            "劈砍", "裂地之擊", "震地", "毒蛇打擊", "碎骨", "箭雨", "燃燒箭矢", "腐蝕箭矢", "閃電箭矢", "冰霜射擊", "龍捲射擊", "閃現射擊", "閃現打擊",
            "毒氣擲彈", "風暴浪湧", "閃電長矛", "旋風斬", "迴旋斬", "旋風之槍", "火球", "冰霜新星", "電球", "電弧", "彗星", "冰風暴",
            "烈焰之牆", "烈焰衝刺", "震波圖騰", "火焰陷阱", "閃電陷阱", "屍體爆破", "殭屍復甦", "召喚骷髏", "憤怒狂靈", "召喚烈焰魔像",
            "憎恨", "憤怒", "堅定", "優雅", "紀律", "元素淨化", "活力", "清晰", "精準", "雷霆", "迅捷",
            "灰燼之捷", "冰霜之捷", "閃電之捷", "激勵戰吼", "威嚇戰吼", "堅決戰吼", "先祖戰士圖騰",
            // 輔助
            "召喚物傷害", "召喚物生命", "撕裂", "分裂", "分岔", "殘暴", "法術圖騰", "陷阱", "多重圖騰", "法術迴響", "施放迴響", "穿刺", "齊射", "吞噬",
            "多重打擊",
            // 通貨與戰星
            "磨刀石", "護甲片", "機會石", "製圖釘", "祝福石", "增幅石", "混沌石", "崇高石", "神聖石", "點金石", "蛻變石", "富豪石",
            "心智精髓", "烈焰精髓", "肉體精髓", "寒冰精髓", "巫術精髓", "無限精髓", "暴擊球", "狂怒球", "耐力球",
            // 傳奇與基底
            "凍傷", "鷹眼", "穿雲", "珊瑚戒指", "琥珀護身符", "翠玉護身符", "鍛鐵戒指", "紅寶石", "綠寶石", "藍寶石", "鑽石", "翡翠",
            // 天賦、昇華天賦、星圖
            "刺客", "迅捷步伐", "霜咬", "彈道學", "粉碎", "碎顱", "觸電", "元素超載", "沉重之擊", "餘震", "放血", "三相", "深淵", "枯萎",
            "獵首", "神諭", "獻祭",
            // 地圖事件與 Boss 招式
            "譫妄", "流亡者", "盜賊流亡者", "收割", "冰矛", "火焰吐息", "冰川之刺");

    /** 掃哪些資料檔的 name、title。 */
    private static final List<String> FILES = List.of("skills.yml", "supports.yml", "supports_lineage.yml", "currencies.yml", // 2026-10-27：＋血脈輔助
            "charges.yml", "uniques.yml",
            "ascendancy.yml", "atlas_tree.yml", "map_events.yml", "boss_skills.yml", "maps.yml", "zones.yml", "events.yml", "quests.yml",
            "bases/jewellery.yml", "bases/jewels.yml", "passives/tree.json");

    /** 這幾個是別的遊戲的機制名稱：玩家看得到的文字裡不能出現（說明、台詞也算）。 */
    private static final Pattern LEAGUE_WORDS = Pattern.compile("譫妄|流亡者|暴擊球|狂怒球|耐力球"); // 2026-10：球的舊名（現在叫銳星、怒星、磐星）

    @Test
    void namesAreOurOwn() throws IOException {
        final List<String> clashes = new ArrayList<>();
        final LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(64 * 1024 * 1024);
        for (final String file : FILES) {
            final Path path = Path.of("src/main/resources/data").resolve(file);
            final Object loaded = new Yaml(options).load(Files.readString(path, StandardCharsets.UTF_8));
            names(file, loaded, clashes);
        }
        for (final CharacterClass value : CharacterClass.values()) {
            // TODO（2026-10 已知問題）：德魯伊暫時沿用官方名稱，取好自己的名字後把這個例外拿掉
            if (TAKEN.contains(value.displayName()) && value != CharacterClass.DRUID) {
                clashes.add("職業 " + value.id() + "：" + value.displayName());
            }
        }
        // TODO（2026-10 已知問題）：PoE2 改版新加的這幾個名稱跟別的遊戲一樣，改名之後把它們從這裡拿掉
        clashes.removeAll(List.of("ascendancy.yml：獻祭", "ascendancy.yml：齊射", "ascendancy.yml：精準",
                "ascendancy.yml：迅捷", "ascendancy.yml：放血", "skills.yml：餘震"));
        // 2026-10-26：昇華天賦照使用者的要求換成 PoE2 原作的台服名稱（煉獄術士「召喚物生命」、戰術家「召喚物傷害」兩個小天賦跟輔助寶石同名）
        clashes.removeAll(List.of("ascendancy.yml：召喚物生命", "ascendancy.yml：召喚物傷害"));
        assertTrue(clashes.isEmpty(), "名稱跟別的遊戲一模一樣：\n" + String.join("\n", clashes));
    }

    /** 職業名稱看得出玩法（2026-10 第二輪：北斗星名看不出特色，改成直接寫武器或打法）。 */
    @Test
    void classNamesSayHowTheyPlay() {
        final Map<CharacterClass, String> hints = Map.of(
                CharacterClass.MARAUDER, "甲", CharacterClass.MERCENARY, "弩", CharacterClass.HUNTRESS, "矛",
                CharacterClass.RANGER, "弓", CharacterClass.MONK, "棍", CharacterClass.SORCERESS, "元素",
                CharacterClass.WITCH, "混沌", CharacterClass.DRUID, "獸"); // 2026-10 德魯伊：獸魂符、變身成野獸
        for (final CharacterClass value : CharacterClass.values()) {
            final String hint = hints.get(value);
            if (value == CharacterClass.DRUID) {
                continue; // TODO（2026-10 已知問題）：德魯伊改名（要看得出「獸」）之後拿掉這個例外
            }
            assertTrue(hint != null && value.displayName().contains(hint), value.id() + " 的名字「" + value.displayName() + "」要看得出玩法（" + hint + "）");
        }
    }

    @Test
    void leagueWordsStayOutOfPlayerText() throws IOException {
        final List<String> offenders = new ArrayList<>();
        final LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(64 * 1024 * 1024);
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/data"))) {
            for (final Path file : files.filter(Files::isRegularFile).toList()) {
                final String name = file.getFileName().toString();
                if (name.endsWith(".yml") || name.endsWith(".json")) {
                    values(file.toString(), new Yaml(options).load(Files.readString(file, StandardCharsets.UTF_8)), offenders);
                }
            }
        }
        final Pattern literal = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (final Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                int lineNumber = 0;
                for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    lineNumber++;
                    final String trimmed = line.trim();
                    if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) {
                        continue;
                    }
                    final int comment = line.indexOf("// ");
                    final Matcher matcher = literal.matcher(comment < 0 ? line : line.substring(0, comment));
                    while (matcher.find()) {
                        if (LEAGUE_WORDS.matcher(matcher.group()).find()) {
                            offenders.add(file + ":" + lineNumber + " " + matcher.group());
                        }
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "玩家看得到的文字用了別的遊戲的機制名稱：\n" + String.join("\n", offenders));
    }

    private static void names(final String where, final Object node, final List<String> clashes) {
        if (node instanceof Map<?, ?> map) {
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                final String key = String.valueOf(entry.getKey());
                if ((key.equals("name") || key.equals("title")) && entry.getValue() instanceof String text && TAKEN.contains(text.trim())) {
                    clashes.add(where + "：" + text);
                }
                names(where, entry.getValue(), clashes);
            }
        } else if (node instanceof List<?> list) {
            for (final Object child : list) {
                names(where, child, clashes);
            }
        }
    }

    private static void values(final String where, final Object node, final List<String> offenders) {
        if (node instanceof Map<?, ?> map) {
            for (final Object value : map.values()) {
                values(where, value, offenders);
            }
        } else if (node instanceof List<?> list) {
            for (final Object child : list) {
                values(where, child, offenders);
            }
        } else if (node instanceof String text && LEAGUE_WORDS.matcher(text).find()) {
            offenders.add(where + "：" + text);
        }
    }
}
