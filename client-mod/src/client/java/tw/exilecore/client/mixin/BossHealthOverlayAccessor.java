package tw.exilecore.client.mixin;

import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/** 2026-11-02 照站長（1.4）：讀畫面上有幾條 Boss 血條（目標框推到最後一條下面）。 */
@Mixin(BossHealthOverlay.class)
public interface BossHealthOverlayAccessor {

    @Accessor("events")
    Map<UUID, LerpingBossEvent> exilecore$events();
}
