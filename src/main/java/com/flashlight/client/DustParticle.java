package com.flashlight.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;

public class DustParticle extends TextureSheetParticle {
   private static final float MAX_ALPHA = 0.45F;
   private final float twinkleSpeed;
   private final float twinklePhase;

   protected DustParticle(ClientLevel world, double x, double y, double z, SpriteSet sprites) {
      super(world, x, y, z);
      this.pickSprite(sprites);
      this.quadSize = 0.012F + this.random.nextFloat() * 0.014F;
      this.lifetime = 60 + this.random.nextInt(80);
      this.gravity = 0.0F;
      this.hasPhysics = false;
      this.xd = (this.random.nextDouble() - 0.5) * 0.004;
      this.yd = (this.random.nextDouble() - 0.5) * 0.003;
      this.zd = (this.random.nextDouble() - 0.5) * 0.004;
      this.twinkleSpeed = 0.15F + this.random.nextFloat() * 0.25F;
      this.twinklePhase = this.random.nextFloat() * (float) (Math.PI * 2);
      this.alpha = 0.0F;
   }

   public void tick() {
      super.tick();
      this.xd *= 1.02;
      this.zd *= 1.02;
      float life = (float)this.age / this.lifetime;
      float fade = Math.min(1.0F, Math.min(life * 6.0F, (1.0F - life) * 4.0F));
      float twinkle = 0.65F + 0.35F * Mth.sin(this.age * this.twinkleSpeed + this.twinklePhase);
      this.alpha = 0.45F * fade * twinkle * BeamRenderer.dustLight(this.x, this.y, this.z);
   }

   protected int getLightColor(float tickDelta) {
      return LightTexture.pack(15, 15);
   }

   public ParticleRenderType getRenderType() {
      return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
   }

   public static class Factory implements ParticleProvider<SimpleParticleType> {
      private final SpriteSet sprites;

      public Factory(SpriteSet sprites) {
         this.sprites = sprites;
      }

      public Particle createParticle(SimpleParticleType type, ClientLevel world, double x, double y, double z, double vx, double vy, double vz) {
         return new DustParticle(world, x, y, z, this.sprites);
      }
   }
}
