package tw.exilecore.client.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tw.exilecore.client.WorldHudClient;

/**
 * 2026-11-02 照站長（模組 1.4 B 組）：有頭上血條的怪，原版名牌只在準心指著或 6 格內才顯示（伺服器的名牌照舊一直開著，
 * 沒裝模組的人看得到）。這次之前名牌一直顯示。
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @Inject(method = "extractNameTags(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FDD)V",
            at = @At("TAIL"))
    private void exilecore$hideNameTag(final Entity entity, final EntityRenderState state, final float partial, final double nameRange,
                                       final double scoreRange, final CallbackInfo ci) {
        if (state.nameTag != null && WorldHudClient.hidesName(entity, state.distanceToCameraSq)) {
            state.nameTag = null;
        }
    }
}
