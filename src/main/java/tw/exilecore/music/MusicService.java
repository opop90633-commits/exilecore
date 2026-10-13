package tw.exilecore.music;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.map.MapInstance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 背景音樂（2026-10，見 {@link Music}）：每秒看一次每個人在哪裡（主世界、地圖、Boss 戰），情境變了就換曲，
 * 一首放完接下一首。Boss 戰＝在地圖裡、Boss 還活著、離 Boss {@value #BOSS_RANGE} 格以內。
 * config.yml 的 music.enabled 可以整個關掉（關掉時原版的背景音樂也還是被資源包靜音）。
 */
public final class MusicService implements Listener {

    /** 離 Boss 幾格以內算「在打 Boss」。 */
    static final double BOSS_RANGE = 48;

    private final ExileCore plugin;
    private final Map<UUID, State> states = new HashMap<>();

    private static final class State {
        Music.Mood mood;
        Music.Track track;
        long endsAt;
        int rotation;
        long since = System.currentTimeMillis();
    }

    public MusicService(final ExileCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.repeat("music", this::tick, 100, 20);
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("music.enabled", true);
    }

    private void tick() {
        if (!enabled()) {
            if (!states.isEmpty()) {
                for (final Player player : plugin.getServer().getOnlinePlayers()) {
                    stop(player, states.get(player.getUniqueId()));
                }
                states.clear();
            }
            return;
        }
        final long now = System.currentTimeMillis();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final State state = states.computeIfAbsent(player.getUniqueId(), id -> new State());
            if (now - state.since < 3000) {
                continue; // 剛進來：等資源包載好
            }
            final Music.Mood mood = mood(player);
            if (mood != state.mood) {
                stop(player, state);
                state.mood = mood;
                play(player, state, next(state, mood), now);
            } else if (now >= state.endsAt) {
                play(player, state, next(state, mood), now);
            }
        }
    }

    /** 這個人現在該聽哪一種。 */
    Music.Mood mood(final Player player) {
        final MapInstance instance = plugin.maps() == null ? null : plugin.maps().instanceAt(player.getLocation());
        if (instance == null) {
            return Music.Mood.OVERWORLD;
        }
        final LivingEntity boss = instance.boss();
        if (boss != null && boss.isValid() && !boss.isDead() && boss.getWorld().equals(player.getWorld())
                && boss.getLocation().distanceSquared(player.getLocation()) <= BOSS_RANGE * BOSS_RANGE) {
            final boolean endgame = instance.map().isFinal() || instance.isUber() || instance.isPinnacle() || instance.singleEntry();
            return endgame ? Music.Mood.ENDGAME_BOSS : Music.Mood.BOSS;
        }
        return Music.Mood.MAP;
    }

    /** 下一首：主世界輪流、地圖隨機（不連續同一首）、Boss 只有一首。 */
    private static Music.Track next(final State state, final Music.Mood mood) {
        final List<Music.Track> list = Music.tracks(mood);
        if (list.size() == 1) {
            return list.getFirst();
        }
        if (mood == Music.Mood.MAP) {
            Music.Track pick;
            do {
                pick = list.get(ThreadLocalRandom.current().nextInt(list.size()));
            } while (pick == state.track);
            return pick;
        }
        state.rotation = (state.rotation + 1) % list.size();
        return list.get(state.rotation);
    }

    private static void play(final Player player, final State state, final Music.Track track, final long now) {
        state.track = track;
        state.endsAt = now + Math.round(track.seconds() * 1000);
        player.playSound(Sound.sound(Key.key("exilecore", track.event()), Sound.Source.MUSIC, 1f, 1f), Sound.Emitter.self());
    }

    private static void stop(final Player player, final State state) {
        if (state != null && state.track != null) {
            player.stopSound(SoundStop.namedOnSource(Key.key("exilecore", state.track.event()), Sound.Source.MUSIC));
            state.track = null;
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }
}
