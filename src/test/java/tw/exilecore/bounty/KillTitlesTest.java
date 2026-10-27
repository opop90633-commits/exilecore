package tw.exilecore.bounty;

import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;

import java.io.StringReader;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 擊殺稱號的發放（2026-10-26：站長回報「毀契者 51／50 還是還沒有」）。
 * 原因：帳號的稱號清單（shop.titles 一筆）上限寫死 250 字，擁有的稱號多了新的記不下，只在 Boss 倒下時試一次、失敗就一直不發。
 */
class KillTitlesTest {

    private static BountyConfig config() {
        final BountyConfig config = new BountyConfig();
        config.load(YamlNode.load("bounties.yml", new StringReader("""
                boss_titles:
                  ritual_priest:   { id: kill_ritual_priest, boss_name: 祭司・血契, name: 毀契者, kills: 50, color: "#e05050",
                                     stats: [ { stat: life.max, type: INCREASED, value: 3 } ] }
                  breach_lord:     { id: kill_breach_lord, boss_name: 裂隙之主・穿界者, name: 縫界之人, kills: 50, color: "#cf8aff" }
                  sky_crucible:    { id: kill_emperor, boss_name: 極境・曜帝, name: 弒帝者, kills: 100, color: "#f5b942" }
                """)));
        return config;
    }

    private static List<String> due(final BountyConfig config, final Map<String, String> progress) {
        return KillTitles.due(config.bossTitles().values(), progress, MeritShop.ownedTitles(progress)).stream()
                .map(def -> def.title().id()).toList();
    }

    @Test
    void grantsExactlyAtTarget() {
        final BountyConfig config = config();
        final Map<String, String> progress = new HashMap<>();
        progress.put(BountyService.BOSS_KILLS + "ritual_priest", "49");
        assertEquals(List.of(), due(config, progress), "49／50 還沒到");
        progress.put(BountyService.BOSS_KILLS + "ritual_priest", "50");
        assertEquals(List.of("kill_ritual_priest"), due(config, progress), "剛好 50／50 就發");
    }

    @Test
    void catchesUpWhenAlreadyPastTarget() {
        final BountyConfig config = config();
        final Map<String, String> progress = new HashMap<>();
        progress.put(BountyService.BOSS_KILLS + "ritual_priest", "51"); // 站長回報的情況
        progress.put(BountyService.BOSS_KILLS + "breach_lord", "80");
        progress.put(BountyService.BOSS_KILLS + "sky_crucible", "99");
        assertEquals(List.of("kill_ritual_priest", "kill_breach_lord"), due(config, progress), "超過目標還沒拿到的全部補發");
        progress.put(MeritShop.TITLES, "kill_breach_lord");
        assertEquals(List.of("kill_ritual_priest"), due(config, progress), "已經有的不重發");
        progress.put(BountyService.BOSS_KILLS + "ritual_priest", "壞掉");
        assertEquals(0, KillTitles.kills(progress, "ritual_priest"));
    }

    @Test
    void manyOwnedTitlesStillLeaveRoomForTheNewOne() {
        // 實際的稱號：功勳商店 + 13 個擊殺稱號 + 活動、節慶、昇華競賽……以前超過 250 字就記不下
        final Set<String> owned = new LinkedHashSet<>(List.of("exile", "hand", "hunter", "echo", "skywalker", "starlord", "kill_emperor",
                "kill_queen", "kill_ferry", "kill_general", "kill_awakener", "kill_vault", "kill_shrine_warden", "kill_strongbox_mimic",
                "kill_breach_lord", "kill_exile_king", "kill_delirium_mirror", "kill_abyss_lich", "event_2026_09", "event_2026_10",
                "festival_halloween", "level_race_first", "level_race_reach"));
        owned.add("kill_ritual_priest");
        final String value = MeritShop.titlesValue(owned, owned);
        assertTrue(value.length() > 250, "這組本來就超過以前的 250 字：" + value.length());
        assertTrue(MeritShop.ownedTitles(Map.of(MeritShop.TITLES, value)).contains("kill_ritual_priest"), "新拿到的稱號記得下");
        final Map<String, String> progress = new HashMap<>(Map.of(MeritShop.TITLES, value,
                BountyService.BOSS_KILLS + "ritual_priest", "51"));
        assertEquals(List.of(), due(config(), progress), "記下來之後就不再是「該發」");
    }

    /** 2026-10-27：管理員拿掉的擊殺稱號，次數還夠也不補發（這次之前登入、開 /title、重讀設定又發回來）。 */
    @Test
    void skipsAdminRevoked() {
        final BountyConfig config = config();
        final Map<String, String> progress = new HashMap<>();
        progress.put(BountyService.BOSS_KILLS + "ritual_priest", "60");
        progress.put(BountyService.BOSS_KILLS + "breach_lord", "60");
        progress.put(KillTitles.REVOKED + "kill_ritual_priest", "1");
        assertTrue(KillTitles.revoked(progress, "kill_ritual_priest"));
        assertEquals(List.of("kill_breach_lord"), due(config, progress), "管理員拿掉的不補發");
        progress.remove(KillTitles.REVOKED + "kill_ritual_priest");
        assertEquals(List.of("kill_ritual_priest", "kill_breach_lord"), due(config, progress), "記號解除後照常");
    }
}
