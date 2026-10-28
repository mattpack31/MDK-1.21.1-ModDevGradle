package com.flashlight.client.mixin;

import com.flashlight.client.BeamRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
abstract class EntityRendererMixin<T extends Entity> {
   // El original apuntaba a "getLight" (nombre intermedio); en nombres Mojang es getPackedLightCoords.
   @Inject(method = "getPackedLightCoords", at = @At("RETURN"), cancellable = true)
   private void flashlight$lightInBeam(T entity, float tickDelta, CallbackInfoReturnable<Integer> cir) {
      int beam = BeamRenderer.entityLight(entity, tickDelta);
      if (beam > 0) {
         int packed = cir.getReturnValueI();
         if (beam > LightTexture.block(packed)) {
            cir.setReturnValue(LightTexture.pack(beam, LightTexture.sky(packed)));
         }
      }
   }
}
