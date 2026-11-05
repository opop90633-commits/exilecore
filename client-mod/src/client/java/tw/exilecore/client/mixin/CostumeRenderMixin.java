package tw.exilecore.client.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tw.exilecore.client.CostumeView;

/**
 * 2026-11-05 照站長「德魯伊第三人稱變形時會隱形」（模組 1.5.0）：自己的變形外觀第一人稱不畫（不擋視線）、第三人稱照畫。
 * 伺服器只對 1.5.0 以上的模組把外觀送給本人；沒裝模組、舊版的照舊看不到自己的變形。
 */
@Mixin(EntityRenderer.class)
public abstract class CostumeRenderMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void exilecore$hideOwnCostume(final Entity entity, final Frustum frustum, final double x, final double y, final double z,
                                          final CallbackInfoReturnable<Boolean> cir) {
        if (CostumeView.hidden(entity)) {
            cir.setReturnValue(false);
        }
    }
}
