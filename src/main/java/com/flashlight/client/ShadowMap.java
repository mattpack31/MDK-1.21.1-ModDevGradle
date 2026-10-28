package com.flashlight.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

final class ShadowMap {
   private static final double WIDEN = 1.04;
   private static final Vec3 UP = new Vec3(0.0, 1.0, 0.0);
   private static final RandomSource RANDOM = RandomSource.create();
   private static final int PATCH = 2;
   private static final double LAYER_PASS = 0.6;
   private static final double FOLIAGE_FLOOR = 0.45;
   static long lastCaptureNanos;
   private DynamicTexture texture;
   int size;
   Vec3 origin;
   Vec3 dir;
   Vec3 u;
   Vec3 v;
   double angle;
   double range;
   long capturedTick = Long.MIN_VALUE;

   int glId() {
      return this.texture.getId();
   }

   boolean moved(Vec3 lens, Vec3 aim) {
      return this.origin == null || this.origin.distanceToSqr(lens) > 1.0E-6 || this.dir.dot(aim) < 0.9999995;
   }

   void capture(ClientLevel world, Vec3 lens, Vec3 aim, double tanSpot, double maxRange, int mapSize, long tick) {
      long started = System.nanoTime();
      if (this.texture == null || this.size != mapSize) {
         if (this.texture != null) {
            this.texture.close();
         }

         this.texture = new DynamicTexture(mapSize, mapSize, false);
         this.size = mapSize;
      }

      Vec3 side = aim.cross(UP);
      if (side.lengthSqr() < 1.0E-6) {
         side = new Vec3(1.0, 0.0, 0.0);
      }

      Vec3 su = side.normalize();
      Vec3 sv = su.cross(aim).normalize();
      double a = Math.atan(tanSpot) * 1.04;
      NativeImage image = this.texture.getPixels();
      if (image != null) {
         for (int j = 0; j < this.size; j++) {
            double q = Math.tan(((j + 0.5) / this.size * 2.0 - 1.0) * a);

            for (int i = 0; i < this.size; i++) {
               double s = Math.tan(((i + 0.5) / this.size * 2.0 - 1.0) * a);
               Vec3 d = aim.add(su.scale(s)).add(sv.scale(q)).normalize();
               ShadowMap.Ray ray = trace(world, lens, lens.add(d.scale(maxRange)), maxRange);
               int encoded = (int)Math.round(Math.min(ray.opaque / maxRange, 1.0) * 65535.0);
               int through = (int)Math.round(ray.transmit * 255.0);
               int leaves = ray.firstLeaves < 0.0 ? 255 : Math.min(254, (int)Math.round(ray.firstLeaves / maxRange * 255.0));
               image.setPixelRGBA(i, j, leaves << 24 | through << 16 | (encoded & 0xFF) << 8 | encoded >>> 8);
            }
         }

         this.texture.upload();
         this.origin = lens;
         this.dir = aim;
         this.u = su;
         this.v = sv;
         this.angle = a;
         this.range = maxRange;
         this.capturedTick = tick;
         lastCaptureNanos = System.nanoTime() - started;
      }
   }

   private static ShadowMap.Ray trace(ClientLevel world, Vec3 from, Vec3 to, double maxRange) {
      ShadowMap.Ray ray = new ShadowMap.Ray();
      Double hit = (Double)BlockGetter.traverseBlocks(from, to, world, (view, pos) -> {
         BlockState state = view.getBlockState(pos);
         if (!state.isAir() && !state.getCollisionShape(view, pos).isEmpty() && !isFoliage(state)) {
            RenderType layer = ItemBlockRenderTypes.getChunkRenderType(state);
            if (layer == RenderType.translucent()) {
               return null;
            }

            VoxelShape shape = state.getShape(view, pos);
            BlockHitResult result = shape.clip(from, to, pos);
            if (result == null) {
               return null;
            }

            double dist = result.getLocation().distanceTo(from);
            if ((layer == RenderType.cutout() || layer == RenderType.cutoutMipped()) && Block.isShapeFullBlock(shape)) {
               ray.transmit = ray.transmit * (0.6 + 0.4 * clearShareAt(state, pos, result));
               if (ray.firstLeaves < 0.0) {
                  ray.firstLeaves = dist;
               }

               return null;
            } else {
               return dist;
            }
         } else {
            return null;
         }
      }, view -> maxRange);
      ray.opaque = hit == null ? maxRange : hit;
      if (ray.firstLeaves >= 0.0) {
         ray.transmit = Math.max(ray.transmit, 0.45);
      }

      return ray;
   }

   /**
    * TODO verificar: el original comparaba contra 8 bloques ofuscados que no pude resolver sin mappings
    * (f_152541_, f_152542_, f_152543_, f_152545_, f_152546_, f_50571_, f_50685_, f_220833_).
    * Esta lista es una SUPOSICION razonable de "follaje por el que pasa la luz"; ajustala si algo se comporta distinto.
    */
   static boolean isFoliage(BlockState state) {
      return state.is(BlockTags.LEAVES)
         || state.is(Blocks.VINE)
         || state.is(Blocks.GLOW_LICHEN)
         || state.is(Blocks.CAVE_VINES)
         || state.is(Blocks.CAVE_VINES_PLANT)
         || state.is(Blocks.SPORE_BLOSSOM)
         || state.is(Blocks.HANGING_ROOTS)
         || state.is(Blocks.AZALEA)
         || state.is(Blocks.FLOWERING_AZALEA);
   }

   private static double clearShareAt(BlockState state, BlockPos pos, BlockHitResult hit) {
      try {
         Direction side = hit.getDirection();
         BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
         RANDOM.setSeed(state.getSeed(pos));
         List<BakedQuad> quads = model.getQuads(state, side, RANDOM);
         if (quads.isEmpty()) {
            return 1.0;
         }

         SpriteContents texture = quads.get(0).getSprite().contents();
         double x = hit.getLocation().x - pos.getX();
         double y = hit.getLocation().y - pos.getY();
         double z = hit.getLocation().z - pos.getZ();
         double u;
         double v;
         switch (side) {
            case NORTH:
               u = 1.0 - x;
               v = 1.0 - y;
               break;
            case SOUTH:
               u = x;
               v = 1.0 - y;
               break;
            case WEST:
               u = z;
               v = 1.0 - y;
               break;
            case EAST:
               u = 1.0 - z;
               v = 1.0 - y;
               break;
            case UP:
               u = x;
               v = z;
               break;
            default:
               u = x;
               v = 1.0 - z;
         }

         int w = texture.width();
         int h = texture.height();
         int cx = (int)(u * w);
         int cy = (int)(v * h);
         int clear = 0;
         int total = 0;

         for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
               if (texture.isTransparent(0, Math.floorMod(cx + dx, w), Math.floorMod(cy + dy, h))) {
                  clear++;
               }

               total++;
            }
         }

         return (double)clear / total;
      } catch (RuntimeException e) {
         return 0.0;
      }
   }

   void close() {
      if (this.texture != null) {
         this.texture.close();
      }
   }

   private static final class Ray {
      double opaque;
      double firstLeaves = -1.0;
      double transmit = 1.0;
   }
}
