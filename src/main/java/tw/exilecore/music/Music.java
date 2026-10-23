package tw.exilecore.music;

import java.util.List;

/**
 * 背景音樂（2026-10）：web/music/曲名.ogg（Pixabay 的曲子，tools/music_src/ 的 mp3 轉的：統一響度、44.1kHz），
 * 放在介面資源包裡（assets/exilecore/sounds/music/曲名.ogg，事件 exilecore:music.曲名），原版的背景音樂在資源包裡換成沒有聲音。
 * <ul>
 *   <li>主世界（營地、劇情世界）：Medieval Abbey</li>
 *   <li>地圖：Moon City</li>
 *   <li>一般 Boss、終局 Boss：Void vs Me</li>
 *   <li>極境、巔峰、金庫之主：Escape from Micikplay</li>
 * </ul>
 * 每一首的長度照 ogg 檔讀（換曲子只要換檔案）。放在「音樂」音量類別：玩家在選項的「音樂」調大小或關掉。
 */
public final class Music {

    private Music() {
    }

    public enum Mood {
        OVERWORLD, MAP,
        /** 一般地圖的 Boss 與終局 Boss。 */
        BOSS,
        /** 極境、巔峰、金庫之主。 */
        UBER_BOSS
    }

    /** 一首曲子。 */
    public record Track(String id, Mood mood) {

        /** 音效事件（資源包 assets/exilecore/sounds.json）。 */
        public String event() {
            return "music." + id;
        }

        /** 一輪多長（秒，照 ogg 檔讀；讀不到當 120 秒）。 */
        public double seconds() {
            return DURATIONS.computeIfAbsent(id, key -> {
                try (java.io.InputStream in = Music.class.getResourceAsStream("/web/music/" + key + ".ogg")) {
                    final double seconds = in == null ? -1 : oggSeconds(in.readAllBytes());
                    return seconds > 0 ? seconds : 120.0;
                } catch (final java.io.IOException e) {
                    return 120.0;
                }
            });
        }
    }

    private static final java.util.Map<String, Double> DURATIONS = new java.util.concurrent.ConcurrentHashMap<>();

    public static final List<Track> TRACKS = List.of(
            new Track("overworld", Mood.OVERWORLD),
            new Track("map", Mood.MAP),
            new Track("boss", Mood.BOSS),
            new Track("boss_uber", Mood.UBER_BOSS));

    /**
     * Ogg Vorbis 的長度（秒）：最後一頁的 granule position（樣本數）÷ 識別標頭裡的取樣率。讀不懂回傳 -1。
     */
    static double oggSeconds(final byte[] data) {
        int rate = -1;
        for (int i = 0; i + 16 < data.length && i < 4096; i++) {
            if (data[i] == 1 && data[i + 1] == 'v' && data[i + 2] == 'o' && data[i + 3] == 'r' && data[i + 4] == 'b'
                    && data[i + 5] == 'i' && data[i + 6] == 's') {
                rate = (data[i + 12] & 0xFF) | (data[i + 13] & 0xFF) << 8 | (data[i + 14] & 0xFF) << 16 | (data[i + 15] & 0xFF) << 24;
                break;
            }
        }
        if (rate <= 0) {
            return -1;
        }
        for (int i = data.length - 14; i >= 0; i--) {
            if (data[i] == 'O' && data[i + 1] == 'g' && data[i + 2] == 'g' && data[i + 3] == 'S') {
                long granule = 0;
                for (int b = 7; b >= 0; b--) {
                    granule = granule << 8 | (data[i + 6 + b] & 0xFF);
                }
                return granule > 0 ? granule / (double) rate : -1;
            }
        }
        return -1;
    }

    /** 這種情境的曲子。 */
    public static List<Track> tracks(final Mood mood) {
        return TRACKS.stream().filter(track -> track.mood() == mood).toList();
    }

    /**
     * 原版的背景音樂事件（Minecraft 26.2）：資源包換成沒有聲音，才不會跟我們的疊在一起。主選單的音樂留著。
     * 2026-10-23：音樂拆成另一包時，這些靜音放在音樂那包（關掉背景音樂的人聽原版的）。
     */
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
