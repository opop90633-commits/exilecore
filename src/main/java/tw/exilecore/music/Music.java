package tw.exilecore.music;

import java.util.List;

/**
 * 背景音樂（2026-10）：7 首程式合成的曲子（tools/compose_music.py 產生，沒有取樣、沒有版權問題），放在介面資源包裡
 * （assets/exilecore/sounds/music/曲名.ogg，事件 exilecore:music.曲名），原版的背景音樂在資源包裡換成沒有聲音。
 * <ul>
 *   <li>主世界（營地、劇情世界）：2 首輪播</li>
 *   <li>地圖：3 首隨機（不連續放同一首）</li>
 *   <li>打 Boss：1 首；終局、極境、巔峰、金庫之主的 Boss 另外 1 首</li>
 * </ul>
 * 放在「音樂」音量類別：玩家在選項的「音樂」調大小或關掉。
 */
public final class Music {

    private Music() {
    }

    public enum Mood {
        OVERWORLD, MAP, BOSS, ENDGAME_BOSS
    }

    /** 一首曲子；seconds 是一輪多長（放完接下一首）。 */
    public record Track(String id, Mood mood, double seconds) {

        /** 音效事件（資源包 assets/exilecore/sounds.json）。 */
        public String event() {
            return "music." + id;
        }
    }

    public static final List<Track> TRACKS = List.of(
            new Track("overworld_1", Mood.OVERWORLD, 32 * 240.0 / 76),
            new Track("overworld_2", Mood.OVERWORLD, 28 * 240.0 / 68),
            new Track("map_1", Mood.MAP, 24 * 240.0 / 60),
            new Track("map_2", Mood.MAP, 32 * 240.0 / 84),
            new Track("map_3", Mood.MAP, 36 * 240.0 / 92),
            new Track("boss", Mood.BOSS, 48 * 240.0 / 140),
            new Track("boss_endgame", Mood.ENDGAME_BOSS, 56 * 240.0 / 150));

    /** 這種情境的曲子。 */
    public static List<Track> tracks(final Mood mood) {
        return TRACKS.stream().filter(track -> track.mood() == mood).toList();
    }

    /** 原版的背景音樂事件（Minecraft 26.2）：資源包換成沒有聲音，才不會跟我們的疊在一起。主選單的音樂留著。 */
    public static final List<String> VANILLA_MUSIC = List.of(
            "music.game", "music.creative", "music.end", "music.dragon", "music.under_water",
            "music.nether.basalt_deltas", "music.nether.crimson_forest", "music.nether.nether_wastes",
            "music.nether.soul_sand_valley", "music.nether.warped_forest",
            "music.overworld.deep_dark", "music.overworld.dripstone_caves", "music.overworld.grove", "music.overworld.jagged_peaks",
            "music.overworld.lush_caves", "music.overworld.swamp", "music.overworld.forest", "music.overworld.old_growth_taiga",
            "music.overworld.meadow", "music.overworld.cherry_grove", "music.overworld.frozen_peaks", "music.overworld.snowy_slopes",
            "music.overworld.stony_peaks", "music.overworld.flower_forest", "music.overworld.desert", "music.overworld.badlands",
            "music.overworld.jungle", "music.overworld.sparse_jungle", "music.overworld.bamboo_jungle");
}
