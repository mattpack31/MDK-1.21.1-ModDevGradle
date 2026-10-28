package com.flashlight.client;

import com.flashlight.FlashlightMod;
import com.mojang.blaze3d.platform.GlStateManager.DestFactor;
import com.mojang.blaze3d.platform.GlStateManager.SourceFactor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ClipContext.Block;
import net.minecraft.world.level.ClipContext.Fluid;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult.Type;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class BeamRenderer {
   private static final ResourceLocation COOKIE = FlashlightMod.id("textures/misc/beam_cookie.png");
   private static final double RANGE = 64.0;
   private static final double FADE_START = 22.0;
   private static double tanSpot = Math.tan(Math.toRadians(30.0));
   private static final double LENS_RADIUS = 0.035;
   private static final int EDGE_RAYS = 24;
   private static final double AIM_FOLLOW_RATE = 9.0;
   private static final double MAX_RENDER_DIST_SQ = 25600.0;
   private static final int SAMPLES_ACROSS = 32;
   private static final int MAX_SUBDIV = 24;
   private static final float GAIN = 1.7F;
   private static final float GLOW = 0.0F;
   private static final double CONTRAST = 2.0;
   private static final int OTHER_SHADOW_INTERVAL = 3;
   private static final double NEAR_COVER = 16.0;
   private static final double NEAR_COVER_WIDTH = 28.0;
   private static final int DUST_PER_TICK = 2;
   private static final double DUST_REACH = 9.0;
   private static final boolean DEBUG_TIMING = Boolean.getBoolean("flashlight.debugTiming");
   private static final double NEAR_FLOOR = 0.8;
   private static final double EDGE_SOFTEN = 0.5;
   private static final double EDGE_SOFTEN_START = 0.55;
   private static final double LIGHT_RANGE = 48.0;
   private static final double SHADER_LIGHT_BASE = 4.0;
   private static final int SHADER_LIGHT_MIN = 5;
   private static final int SHADER_LIGHT_MAX = 9;
   private static final int QUAD_BUDGET = 14000;
   private static final float CR = 1.0F;
   private static final float CG = 0.975F;
   private static final float CB = 0.94F;
   private static final Direction[] DIRECTIONS = Direction.values();
   private static final RandomSource RANDOM = RandomSource.create();
   private static final int STRIDE = 9;
   private static final Map<UUID, Vec3> AIM = new HashMap<>();
   private static final Map<UUID, ShadowMap> SHADOW_MAPS = new HashMap<>();
   private static long lastFrameNanos;
   private static final FloatArrayList SPOT = new FloatArrayList();
   static ShaderInstance shader;
   private static final List<BeamRenderer.LightBeam> LIGHT_BEAMS = new ArrayList<>();

   private BeamRenderer() {
   }

   @Nullable
   private static ShadowMap shadowMapFor(ClientLevel world, BeamRenderer.Beam b, boolean yours) {
      int size = FlashlightConfig.get().shadowMapSize();
      if (size == 0) {
         return null;
      }

      ShadowMap map = SHADOW_MAPS.computeIfAbsent(b.owner.getUUID(), id -> new ShadowMap());
      long tick = world.getGameTime();
      boolean due = map.origin == null || map.size != size || tick < map.capturedTick || tick - map.capturedTick >= (yours ? 1 : 3);
      if (due || yours && map.moved(b.shadowTarget, b.dir)) {
         map.capture(world, b.shadowTarget, b.dir, tanSpot, 64.0, size, tick);
         if (DEBUG_TIMING && yours) {
            FlashlightModClient.LOGGER.info("shadow map {}x{} took {} ms", new Object[]{size, size, ShadowMap.lastCaptureNanos / 1000000.0});
         }
      }

      return map;
   }

   public static void render(ClientLevel world, Camera camera, float tickDelta, PoseStack matrices) {
      Minecraft mc = Minecraft.getInstance();
      if (world != null && camera != null) {
         Vec3 cam = camera.getPosition();
         Matrix4f matrix = matrices.last().pose();
         tanSpot = FlashlightConfig.get().tanBeam();
         double seconds = ((float)world.getGameTime() + tickDelta) / 20.0;
         long now = Util.getNanos();
         double dt = lastFrameNanos == 0L ? 0.0 : Mth.clamp((now - lastFrameNanos) / 1.0E9, 0.0, 0.25);
         lastFrameNanos = now;
         Set<UUID> lit = new HashSet<>();
         boolean drawPattern = shader != null && !ShaderPacks.inUse();

         for (AbstractClientPlayer player : world.players()) {
            if (!player.isSpectator() && FlashlightModClient.isLit(player) && !(player.position().distanceToSqr(cam) > 25600.0)) {
               lit.add(player.getUUID());
               Vec3 aim = smoothAim(player, player.getViewVector(tickDelta), dt);
               if (drawPattern) {
                  float flicker = Flicker.brightness(player.getUUID(), seconds);
                  if (!(flicker < 0.01F)) {
                     BeamRenderer.Beam beam = buildBeam(mc, world, player, aim, tickDelta);
                     if (beam != null) {
                        SPOT.clear();
                        projectOnto(mc, world, beam, cam);
                        if (!SPOT.isEmpty()) {
                           drawSpot(beam, cam, matrix, shadowMapFor(world, beam, player == mc.getCameraEntity()), flicker);
                        }
                     }
                  }
               }
            }
         }

         AIM.keySet().retainAll(lit);
         SHADOW_MAPS.entrySet().removeIf(e -> {
            if (lit.contains(e.getKey())) {
               return false;
            }

            e.getValue().close();
            return true;
         });
      }
   }

   public static void prepareLights(ClientLevel world, Camera camera, float tickDelta) {
      LIGHT_BEAMS.clear();
      if (world != null && camera != null) {
         double tan = FlashlightConfig.get().tanBeam();
         double seconds = ((float)world.getGameTime() + tickDelta) / 20.0;

         for (AbstractClientPlayer player : world.players()) {
            if (!player.isSpectator() && FlashlightModClient.isLit(player) && !(player.position().distanceToSqr(camera.getPosition()) > 25600.0)) {
               float brightness = Flicker.brightness(player.getUUID(), seconds);
               if (!(brightness < 0.01F)) {
                  Vec3 dir = AIM.getOrDefault(player.getUUID(), player.getViewVector(tickDelta));
                  Vec3 u = dir.cross(new Vec3(0.0, 1.0, 0.0));
                  if (u.lengthSqr() < 1.0E-6) {
                     u = new Vec3(1.0, 0.0, 0.0);
                  }

                  u = u.normalize();
                  Vec3 v = u.cross(dir).normalize();
                  LIGHT_BEAMS.add(new BeamRenderer.LightBeam(player, player.getEyePosition(tickDelta), dir, u, v, tan, brightness));
               }
            }
         }
      }
   }

   public static int entityLight(Entity entity, float tickDelta) {
      if (LIGHT_BEAMS.isEmpty()) {
         return 0;
      }

      ClientLevel world = Minecraft.getInstance().level;
      if (world == null) {
         return 0;
      }

      Vec3 center = entity.getPosition(tickDelta).add(0.0, entity.getBbHeight() * 0.5, 0.0);
      double best = 0.0;

      for (BeamRenderer.LightBeam b : LIGHT_BEAMS) {
         if (b.owner() != entity) {
            Vec3 w = center.subtract(b.origin());
            double t = w.dot(b.dir());
            if (!(t < 0.3) && !(t > 64.0)) {
               double radial = Math.sqrt(Math.max(0.0, w.lengthSqr() - t * t));
               double r = Math.max(0.0, radial - entity.getBbWidth() * 0.5) / (t * b.tan());
               if (!(r >= 1.0)) {
                  double amount = (1.0 - smoothstep(0.55, 1.0, r)) * Math.min(1.0, falloff(t) * 1.4) * distanceFade(t) * b.brightness();
                  if (!(amount <= best)) {
                     BlockHitResult blocked = throughFoliage(world, b.origin(), center);
                     if (blocked == null || !(blocked.getLocation().distanceTo(b.origin()) < w.length() - entity.getBbWidth())) {
                        best = amount;
                     }
                  }
               }
            }
         }
      }

      return (int)Math.round(15.0 * Mth.clamp(best, 0.0, 1.0));
   }

   public static float dustLight(double x, double y, double z) {
      float best = 0.0F;

      for (BeamRenderer.LightBeam b : LIGHT_BEAMS) {
         double wx = x - b.origin().x;
         double wy = y - b.origin().y;
         double wz = z - b.origin().z;
         double t = wx * b.dir().x + wy * b.dir().y + wz * b.dir().z;
         if (!(t < 0.5)) {
            double radial = Math.sqrt(Math.max(0.0, wx * wx + wy * wy + wz * wz - t * t));
            double r = radial / (t * b.tan());
            if (!(r >= 1.0)) {
               best = Math.max(best, (float)((1.0 - smoothstep(0.4, 1.0, r)) * b.brightness() / (1.0 + t / 8.0)));
            }
         }
      }

      return best;
   }

   private static void spawnDust(Minecraft mc, ClientLevel world) {
      if (FlashlightConfig.get().dust && !LIGHT_BEAMS.isEmpty()) {
         RandomSource random = world.getRandom();

         for (BeamRenderer.LightBeam b : LIGHT_BEAMS) {
            for (int k = 0; k < 2; k++) {
               double t = 1.0 + random.nextDouble() * 9.0;
               double radius = Math.sqrt(random.nextDouble()) * t * b.tan() * 0.9;
               double a = random.nextDouble() * Math.PI * 2.0;
               Vec3 p = b.origin().add(b.dir().scale(t)).add(b.u().scale(Math.cos(a) * radius)).add(b.v().scale(Math.sin(a) * radius));
               BlockPos at = BlockPos.containing(p);
               if (world.getBlockState(at).getCollisionShape(world, at).isEmpty()) {
                  mc.particleEngine.createParticle((ParticleOptions)FlashlightMod.DUST.get(), p.x, p.y, p.z, 0.0, 0.0, 0.0);
               }
            }
         }
      }
   }

   private static double smoothstep(double e0, double e1, double x) {
      double t = Mth.clamp((x - e0) / (e1 - e0), 0.0, 1.0);
      return t * t * (3.0 - 2.0 * t);
   }

   public static void tickLights(Minecraft mc) {
      ClientLevel world = mc.level;
      if (world != null) {
         spawnDust(mc, world);
         Map<BlockPos, Integer> wanted = new HashMap<>();
         if (!ShaderPacks.inUse()) {
            GhostLights.update(world, wanted);
         } else {
            tanSpot = FlashlightConfig.get().tanBeam();
            double seconds = world.getGameTime() / 20.0;

            for (AbstractClientPlayer player : world.players()) {
               if (!player.isSpectator() && FlashlightModClient.isLit(player) && (mc.player == null || !(player.distanceToSqr(mc.player) > 25600.0))) {
                  float flicker = Flicker.brightness(player.getUUID(), seconds);
                  if (!(flicker < 0.2F)) {
                     Vec3 eye = player.getEyePosition();
                     Vec3 aim = AIM.getOrDefault(player.getUUID(), player.getViewVector(1.0F));
                     BlockHitResult blockHit = throughFoliage(world, eye, eye.add(aim.scale(48.0)));
                     if (blockHit != null) {
                        HitResult hit = blockHit;
                        double radius = eye.distanceTo(hit.getLocation()) * tanSpot;
                        int level = Mth.clamp((int)Math.round((4.0 + radius * 2.0) * flicker), 5, 9);
                        BlockPos spot = openSpotNear(world, blockHit, aim);
                        if (spot != null) {
                           wanted.merge(spot, level, Math::max);
                        }

                        if (radius > 1.5) {
                           Vec3 u = aim.cross(new Vec3(0.0, 1.0, 0.0));
                           if (u.lengthSqr() < 1.0E-6) {
                              u = new Vec3(1.0, 0.0, 0.0);
                           }

                           u = u.normalize();
                           Vec3 v = u.cross(aim).normalize();

                           for (int k = 0; k < 4; k++) {
                              double a = k * Math.PI / 2.0 + (Math.PI / 4);
                              Vec3 d = aim.add(u.scale(Math.cos(a) * tanSpot * 0.55)).add(v.scale(Math.sin(a) * tanSpot * 0.55)).normalize();
                              BlockHitResult rimHit = throughFoliage(world, eye, eye.add(d.scale(48.0)));
                              if (rimHit != null) {
                                 BlockPos pos = openSpotNear(world, rimHit, d);
                                 if (pos != null) {
                                    wanted.merge(pos, level - 1, Math::max);
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }

            GhostLights.update(world, wanted);
         }
      }
   }

   @Nullable
   private static BlockHitResult throughFoliage(ClientLevel world, Vec3 from, Vec3 to) {
      return (BlockHitResult)BlockGetter.traverseBlocks(
         from,
         to,
         world,
         (view, pos) -> {
            BlockState state = view.getBlockState(pos);
            return !state.isAir() && !state.getCollisionShape(view, pos).isEmpty() && !ShadowMap.isFoliage(state)
               ? state.getShape(view, pos).clip(from, to, pos)
               : null;
         },
         view -> null
      );
   }

   @Nullable
   private static BlockPos openSpotNear(ClientLevel world, BlockHitResult hit, Vec3 aim) {
      BlockPos[] candidates = new BlockPos[]{
         hit.getBlockPos().relative(hit.getDirection()),
         BlockPos.containing(hit.getLocation().subtract(aim.scale(0.75))),
         BlockPos.containing(hit.getLocation().subtract(aim.scale(1.5)))
      };

      for (BlockPos pos : candidates) {
         if (isOpen(world.getBlockState(pos))) {
            return pos;
         }
      }

      return null;
   }

   private static boolean isOpen(BlockState state) {
      return state.isAir() || state.is(Blocks.LIGHT) || state.is(Blocks.WATER) && state.getFluidState().isSource();
   }

   private static void drawSpot(BeamRenderer.Beam b, Vec3 cam, Matrix4f m, ShadowMap shadows, float brightness) {
      BufferBuilder bb = Tesselator.getInstance().begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

      for (int i = 0; i < SPOT.size(); i += 9) {
         bb.addVertex(m, SPOT.getFloat(i), SPOT.getFloat(i + 1), SPOT.getFloat(i + 2))
            .setColor(SPOT.getFloat(i + 5), SPOT.getFloat(i + 6), SPOT.getFloat(i + 7), SPOT.getFloat(i + 8))
            .setUv(SPOT.getFloat(i + 3), SPOT.getFloat(i + 4));
      }

      MeshData mesh = bb.build();
      if (mesh == null) {
         return;
      }

      RenderSystem.enableBlend();
      RenderSystem.blendFunc(SourceFactor.ONE, DestFactor.ONE);
      RenderSystem.disableCull();
      RenderSystem.enableDepthTest();
      RenderSystem.depthMask(false);
      RenderSystem.enablePolygonOffset();
      RenderSystem.polygonOffset(-2.0F, -6.0F);
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
      RenderSystem.setShader(() -> shader);
      RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
      RenderSystem.setShaderTexture(1, COOKIE);
      setPosition("BeamOrigin", m, b.origin, cam);
      setDirection("BeamDir", m, b.dir);
      setDirection("BeamU", m, b.u);
      setDirection("BeamV", m, b.v);
      shader.safeGetUniform("TanSpot").set((float)tanSpot);
      shader.safeGetUniform("LensRadius").set(0.035F);
      shader.safeGetUniform("Contrast").set(2.0F);
      shader.safeGetUniform("EdgeSoften").set(0.5F);
      shader.safeGetUniform("EdgeStart").set(0.55F);
      shader.safeGetUniform("Gain").set(1.7F * brightness);
      shader.safeGetUniform("Glow").set(0.0F * brightness);
      shader.safeGetUniform("LightColor").set(1.0F, 0.975F, 0.94F);
      boolean hasShadows = shadows != null && shadows.origin != null;
      shader.safeGetUniform("ShadowEnabled").set(hasShadows ? 1.0F : 0.0F);
      if (hasShadows) {
         RenderSystem.setShaderTexture(2, shadows.glId());
         setPosition("ShadowOrigin", m, shadows.origin, cam);
         setDirection("ShadowDir", m, shadows.dir);
         setDirection("ShadowU", m, shadows.u);
         setDirection("ShadowV", m, shadows.v);
         shader.safeGetUniform("ShadowAngle").set((float)shadows.angle);
         shader.safeGetUniform("ShadowRange").set((float)shadows.range);
         shader.safeGetUniform("ShadowSize").set(shadows.size);
      } else {
         RenderSystem.setShaderTexture(2, COOKIE);
      }

      BufferUploader.drawWithShader(mesh);
      RenderSystem.disablePolygonOffset();
      RenderSystem.depthMask(true);
      RenderSystem.enableCull();
      RenderSystem.defaultBlendFunc();
      RenderSystem.disableBlend();
   }

   private static void setPosition(String name, Matrix4f m, Vec3 p, Vec3 cam) {
      Vector3f v = m.transformPosition(new Vector3f((float)(p.x - cam.x), (float)(p.y - cam.y), (float)(p.z - cam.z)));
      shader.safeGetUniform(name).set(v.x, v.y, v.z);
   }

   private static void setDirection(String name, Matrix4f m, Vec3 d) {
      Vector3f v = m.transformDirection(new Vector3f((float)d.x, (float)d.y, (float)d.z));
      shader.safeGetUniform(name).set(v.x, v.y, v.z);
   }

   private static Vec3 smoothAim(Player player, Vec3 look, double dt) {
      Vec3 prev = AIM.get(player.getUUID());
      Vec3 aim;
      if (prev != null && !(prev.dot(look) < -0.5)) {
         double k = 1.0 - Math.exp(-dt * 9.0);
         aim = prev.add(look.subtract(prev).scale(k)).normalize();
      } else {
         aim = look;
      }

      AIM.put(player.getUUID(), aim);
      return aim;
   }

   private static BeamRenderer.Beam buildBeam(Minecraft mc, ClientLevel world, Player player, Vec3 aim, float tickDelta) {
      Vec3 eye = player.getEyePosition(tickDelta);
      HitResult hit = world.clip(new ClipContext(eye, eye.add(aim.scale(64.0)), Block.OUTLINE, Fluid.NONE, player));
      BeamRenderer.Beam b = new BeamRenderer.Beam();
      b.owner = player;
      b.hitBlock = hit.getType() == Type.BLOCK;
      b.hit = b.hitBlock ? hit.getLocation() : eye.add(aim.scale(64.0));
      Vec3 look = player.getViewVector(tickDelta);
      double yaw = Math.toRadians(player.getViewYRot(tickDelta));
      Vec3 right = new Vec3(-Math.cos(yaw), 0.0, -Math.sin(yaw));
      Vec3 up = right.cross(look).normalize();
      HumanoidArm arm = player.getMainArm();
      double side = arm == HumanoidArm.RIGHT ? 1.0 : -1.0;
      boolean firstPerson = player == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson();
      Vec3 origin = firstPerson
         ? eye.add(look.scale(0.35)).add(right.scale(0.16 * side)).add(up.scale(-0.14))
         : eye.add(look.scale(0.5)).add(right.scale(0.38 * side)).add(up.scale(-0.48));
      if (b.hit.subtract(origin).dot(look) < 0.15) {
         origin = eye;
      }

      Vec3 delta = b.hit.subtract(origin);
      b.origin = origin;
      b.dir = delta.length() > 1.0E-4 ? delta.normalize() : aim;
      b.nearest = 0.8 * delta.length();
      Vec3 u = b.dir.cross(new Vec3(0.0, 1.0, 0.0));
      if (u.lengthSqr() < 1.0E-6) {
         u = new Vec3(1.0, 0.0, 0.0);
      }

      b.u = u.normalize();
      b.v = b.u.cross(b.dir).normalize();
      BlockPos originPos = BlockPos.containing(origin);
      b.shadowTarget = world.getBlockState(originPos).getCollisionShape(world, originPos).isEmpty() ? origin : eye;
      return b;
   }

   @Nullable
   private static AABB spotRegion(ClientLevel world, BeamRenderer.Beam b) {
      double minX = b.hit.x;
      double minZ = b.hit.z;
      double maxX = minX;
      double maxZ = minZ;
      double minY = Double.POSITIVE_INFINITY;
      double maxY = Double.NEGATIVE_INFINITY;
      if (b.hitBlock) {
         maxY = b.hit.y;
         minY = b.hit.y;
      }

      BlockHitResult behind = throughFoliage(world, b.origin, b.origin.add(b.dir.scale(64.0)));
      if (behind != null) {
         Vec3 p = behind.getLocation();
         minX = Math.min(minX, p.x);
         minZ = Math.min(minZ, p.z);
         maxX = Math.max(maxX, p.x);
         maxZ = Math.max(maxZ, p.z);
         minY = Math.min(minY, p.y);
         maxY = Math.max(maxY, p.y);
      }

      for (int k = 0; k < 24; k++) {
         double a = k * Math.PI * 2.0 / 24.0;

         for (double rim : new double[]{0.15, 0.3, 0.45, 0.6, 0.75, 0.9, 1.0}) {
            double spread = tanSpot * rim;
            Vec3 d = b.dir.add(b.u.scale(Math.cos(a) * spread)).add(b.v.scale(Math.sin(a) * spread)).normalize();
            HitResult hit = world.clip(new ClipContext(b.origin, b.origin.add(d.scale(64.0)), Block.OUTLINE, Fluid.NONE, b.owner));
            Vec3 p = hit.getLocation();
            minX = Math.min(minX, p.x);
            minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x);
            maxZ = Math.max(maxZ, p.z);
            if (hit.getType() == Type.BLOCK) {
               minY = Math.min(minY, p.y);
               maxY = Math.max(maxY, p.y);
            }

            BlockHitResult past = throughFoliage(world, b.origin, b.origin.add(d.scale(64.0)));
            if (past != null) {
               Vec3 q = past.getLocation();
               minX = Math.min(minX, q.x);
               minZ = Math.min(minZ, q.z);
               maxX = Math.max(maxX, q.x);
               maxZ = Math.max(maxZ, q.z);
               minY = Math.min(minY, q.y);
               maxY = Math.max(maxY, q.y);
            }
         }
      }

      return minY > maxY ? null : new AABB(minX - 1.0, minY - 2.0, minZ - 1.0, maxX + 1.0, maxY + 2.0, maxZ + 1.0);
   }

   private static AABB nearCone(BeamRenderer.Beam b) {
      double reach = Math.min(16.0, 28.0 / Math.max(tanSpot, 0.1));
      AABB box = new AABB(b.origin, b.origin);

      for (int sx = -1; sx <= 1; sx += 2) {
         for (int sy = -1; sy <= 1; sy += 2) {
            Vec3 corner = b.dir.add(b.u.scale(sx * tanSpot)).add(b.v.scale(sy * tanSpot)).normalize();
            Vec3 p = b.origin.add(corner.scale(reach));
            box = box.minmax(new AABB(p, p));
         }
      }

      Vec3 tip = b.origin.add(b.dir.scale(reach));
      return box.minmax(new AABB(tip, tip)).inflate(1.0);
   }

   private static void projectOnto(Minecraft mc, ClientLevel world, BeamRenderer.Beam b, Vec3 cam) {
      double dist = b.hit.distanceTo(b.origin);
      double spotRadius = dist * tanSpot + 0.035;
      AABB region = spotRegion(world, b);
      AABB near = nearCone(b);
      region = region == null ? near : region.minmax(near);
      int x0 = Mth.floor(region.minX);
      int y0 = Mth.floor(region.minY);
      int z0 = Mth.floor(region.minZ);
      int x1 = Mth.floor(region.maxX);
      int y1 = Mth.floor(region.maxY);
      int z1 = Mth.floor(region.maxZ);
      BlockRenderDispatcher blocks = mc.getBlockRenderer();
      BlockColors colors = mc.getBlockColors();
      MutableBlockPos pos = new MutableBlockPos();
      MutableBlockPos neighbor = new MutableBlockPos();
      List<BeamRenderer.Candidate> candidates = new ArrayList<>();

      for (int x = x0; x <= x1; x++) {
         for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
               pos.set(x, y, z);
               double wx = pos.getX() + 0.5 - b.origin.x;
               double wy = pos.getY() + 0.5 - b.origin.y;
               double wz = pos.getZ() + 0.5 - b.origin.z;
               double t = wx * b.dir.x + wy * b.dir.y + wz * b.dir.z;
               if (!(t < 0.2) && !(t > 65.0)) {
                  double lim = t * tanSpot + 0.9;
                  if (!(wx * wx + wy * wy + wz * wz - t * t > lim * lim)) {
                     BlockState state = world.getBlockState(pos);
                     BakedQuad water = waterSurface(world, pos, state);
                     if (water != null) {
                        candidates.add(new BeamRenderer.Candidate(Blocks.WATER.defaultBlockState(), pos.immutable(), Vec3.ZERO, water));
                     }

                     if (state.getRenderShape() == RenderShape.MODEL) {
                        BakedModel model = blocks.getBlockModel(state);
                        Vec3 offset = state.getOffset(world, pos);
                        long seed = state.getSeed(pos);
                        BlockPos at = pos.immutable();

                        for (Direction face : DIRECTIONS) {
                           RANDOM.setSeed(seed);
                           List<BakedQuad> quads = model.getQuads(state, face, RANDOM);
                           if (!quads.isEmpty()) {
                              neighbor.setWithOffset(pos, face);
                              if (net.minecraft.world.level.block.Block.shouldRenderFace(state, world, pos, face, neighbor)
                                 && !(face.getStepX() * wx + face.getStepY() * wy + face.getStepZ() * wz > -0.5)) {
                                 for (BakedQuad quad : quads) {
                                    candidates.add(new BeamRenderer.Candidate(state, at, offset, quad));
                                 }
                              }
                           }
                        }

                        RANDOM.setSeed(seed);

                        for (BakedQuad quad : model.getQuads(state, null, RANDOM)) {
                           candidates.add(new BeamRenderer.Candidate(state, at, offset, quad));
                        }
                     }
                  }
               }
            }
         }
      }

      int wanted = Math.max(Mth.ceil(32.0 / (2.0 * spotRadius)), 2);
      int affordable = (int)Math.sqrt(14000.0 / Math.max(1, candidates.size()));
      int subdiv = Mth.clamp(Math.min(wanted, affordable), 1, 24);

      for (BeamRenderer.Candidate c : candidates) {
         addQuad(world, colors, b, cam, c.state(), c.pos(), c.offset(), c.quad(), subdiv);
      }
   }

   @Nullable
   private static BakedQuad waterSurface(ClientLevel world, BlockPos pos, BlockState state) {
      FluidState fluid = state.getFluidState();
      if (!fluid.isEmpty() && fluid.is(FluidTags.WATER)) {
         if (world.getFluidState(pos.above()).is(FluidTags.WATER)) {
            return null;
         }

         TextureAtlasSprite sprite = (TextureAtlasSprite)Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(ResourceLocation.withDefaultNamespace("block/water_still"));
         float[][] corners = new float[][]{{0.0F, 0.0F}, {0.0F, 1.0F}, {1.0F, 1.0F}, {1.0F, 0.0F}};
         float own = waterHeight(world, pos);
         float[] h = new float[4];

         for (int i = 0; i < 4; i++) {
            int dx = corners[i][0] == 0.0F ? -1 : 1;
            int dz = corners[i][1] == 0.0F ? -1 : 1;
            h[i] = cornerHeight(world, own, waterHeight(world, pos.offset(0, 0, dz)), waterHeight(world, pos.offset(dx, 0, 0)), pos.offset(dx, 0, dz));
         }

         float lift = 0.01F + Math.abs(h[0] + h[2] - h[1] - h[3]) * 0.25F;
         int[] data = new int[32];

         for (int i = 0; i < 4; i++) {
            int k = i * 8;
            data[k] = Float.floatToRawIntBits(corners[i][0]);
            data[k + 1] = Float.floatToRawIntBits(h[i] + lift);
            data[k + 2] = Float.floatToRawIntBits(corners[i][1]);
            data[k + 3] = -1;
            data[k + 4] = Float.floatToRawIntBits(sprite.getU(corners[i][0]));
            data[k + 5] = Float.floatToRawIntBits(sprite.getV(corners[i][1]));
         }

         return new BakedQuad(data, 0, Direction.UP, sprite, false);
      } else {
         return null;
      }
   }

   private static float waterHeight(ClientLevel world, BlockPos pos) {
      BlockState state = world.getBlockState(pos);
      FluidState fluid = state.getFluidState();
      if (fluid.is(FluidTags.WATER)) {
         return world.getFluidState(pos.above()).is(FluidTags.WATER) ? 1.0F : fluid.getHeight(world, pos);
      } else {
         return state.blocksMotion() ? -1.0F : 0.0F;
      }
   }

   private static float cornerHeight(ClientLevel world, float own, float northSouth, float eastWest, BlockPos diagonal) {
      if (!(northSouth >= 1.0F) && !(eastWest >= 1.0F)) {
         float[] sum = new float[2];
         if (northSouth > 0.0F || eastWest > 0.0F) {
            float d = waterHeight(world, diagonal);
            if (d >= 1.0F) {
               return 1.0F;
            }

            addHeight(sum, d);
         }

         addHeight(sum, own);
         addHeight(sum, northSouth);
         addHeight(sum, eastWest);
         return sum[1] == 0.0F ? own : sum[0] / sum[1];
      } else {
         return 1.0F;
      }
   }

   private static void addHeight(float[] sum, float height) {
      if (height >= 0.8F) {
         sum[0] += height * 10.0F;
         sum[1] += 10.0F;
      } else if (height >= 0.0F) {
         sum[0] += height;
         sum[1]++;
      }
   }

   private static void addQuad(
      ClientLevel world, BlockColors colors, BeamRenderer.Beam b, Vec3 cam, BlockState state, BlockPos pos, Vec3 offset, BakedQuad quad, int n
   ) {
      int[] data = quad.getVertices();
      int stride = data.length / 4;
      double[] px = new double[4];
      double[] py = new double[4];
      double[] pz = new double[4];
      float[] tu = new float[4];
      float[] tv = new float[4];

      for (int i = 0; i < 4; i++) {
         int k = i * stride;
         px[i] = Float.intBitsToFloat(data[k]) + pos.getX() + offset.x;
         py[i] = Float.intBitsToFloat(data[k + 1]) + pos.getY() + offset.y;
         pz[i] = Float.intBitsToFloat(data[k + 2]) + pos.getZ() + offset.z;
         tu[i] = Float.intBitsToFloat(data[k + 4]);
         tv[i] = Float.intBitsToFloat(data[k + 5]);
      }

      Direction face = quad.getDirection();
      double nx = face.getStepX();
      double ny = face.getStepY();
      double nz = face.getStepZ();
      double cx = (px[0] + px[2]) * 0.5;
      double cy = (py[0] + py[2]) * 0.5;
      double cz = (pz[0] + pz[2]) * 0.5;
      double fx = cx - b.origin.x;
      double fy = cy - b.origin.y;
      double fz = cz - b.origin.z;
      double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
      double lambert = -(nx * fx + ny * fy + nz * fz) / fl;
      if (!(lambert <= 0.01)) {
         double minS = 9.0;
         double maxS = -9.0;
         double minQ = 9.0;
         double maxQ = -9.0;

         for (int i = 0; i < 4; i++) {
            double[] sq = project(b, px[i], py[i], pz[i]);
            if (sq == null) {
               return;
            }

            minS = Math.min(minS, sq[0]);
            maxS = Math.max(maxS, sq[0]);
            minQ = Math.min(minQ, sq[1]);
            maxQ = Math.max(maxQ, sq[1]);
         }

         if (!(minS > 1.0) && !(maxS < -1.0) && !(minQ > 1.0) && !(maxQ < -1.0)) {
            float tr = 1.0F;
            float tg = 0.975F;
            float tb = 0.94F;
            if (quad.isTinted()) {
               int c = colors.getColor(state, world, pos, quad.getTintIndex());
               tr *= (c >> 16 & 0xFF) / 255.0F;
               tg *= (c >> 8 & 0xFF) / 255.0F;
               tb *= (c & 0xFF) / 255.0F;
            }

            BlockPos lightPos = BlockPos.containing(cx + nx * 0.5, cy + ny * 0.5, cz + nz * 0.5);
            int sky = world.getBrightness(LightLayer.SKY, lightPos) - world.getSkyDarken();
            float daylight = Mth.clamp(sky / 15.0F, 0.0F, 1.0F);
            float base = 1.7F * (float)Math.pow(lambert, 1.0) * (1.0F - 0.5F * (float)Math.pow(daylight, 1.5));
            int side = n + 1;
            float[] vert = new float[side * side * 6];

            for (int j = 0; j <= n; j++) {
               for (int i = 0; i <= n; i++) {
                  double a = (double)i / n;
                  double c = (double)j / n;
                  double x = bilerp(px, a, c);
                  double y = bilerp(py, a, c);
                  double z = bilerp(pz, a, c);
                  double[] sq = project(b, x, y, z);
                  float light = 0.0F;
                  if (sq != null) {
                     light = base * (float)falloff(Math.max(sq[2], b.nearest)) * distanceFade(sq[2]);
                  }

                  int k = (j * side + i) * 6;
                  vert[k] = (float)(x + nx * 0.001 - cam.x);
                  vert[k + 1] = (float)(y + ny * 0.001 - cam.y);
                  vert[k + 2] = (float)(z + nz * 0.001 - cam.z);
                  vert[k + 3] = (float)bilerp(tu, a, c);
                  vert[k + 4] = (float)bilerp(tv, a, c);
                  vert[k + 5] = light;
               }
            }

            for (int j = 0; j < n; j++) {
               for (int i = 0; i < n; i++) {
                  int i0 = j * side + i;
                  int i1 = i0 + 1;
                  int i2 = i0 + side + 1;
                  int i3 = i0 + side;
                  if (!(vert[i0 * 6 + 5] + vert[i1 * 6 + 5] + vert[i2 * 6 + 5] + vert[i3 * 6 + 5] < 0.004F)) {
                     emitSpot(vert, i0, tr, tg, tb);
                     emitSpot(vert, i1, tr, tg, tb);
                     emitSpot(vert, i2, tr, tg, tb);
                     emitSpot(vert, i3, tr, tg, tb);
                  }
               }
            }
         }
      }
   }

   private static void emitSpot(float[] vert, int index, float r, float g, float b) {
      int k = index * 6;
      SPOT.add(vert[k]);
      SPOT.add(vert[k + 1]);
      SPOT.add(vert[k + 2]);
      SPOT.add(vert[k + 3]);
      SPOT.add(vert[k + 4]);
      SPOT.add(r);
      SPOT.add(g);
      SPOT.add(b);
      SPOT.add(Mth.clamp(vert[k + 5] / 1.7F, 0.0F, 1.0F));
   }

   private static double[] project(BeamRenderer.Beam b, double x, double y, double z) {
      double wx = x - b.origin.x;
      double wy = y - b.origin.y;
      double wz = z - b.origin.z;
      double t = wx * b.dir.x + wy * b.dir.y + wz * b.dir.z;
      if (t <= 0.05) {
         return null;
      }

      double scale = 1.0 / (t * tanSpot + 0.035);
      double s = (wx * b.u.x + wy * b.u.y + wz * b.u.z) * scale;
      double q = (wx * b.v.x + wy * b.v.y + wz * b.v.z) * scale;
      return new double[]{s, q, t};
   }

   private static float distanceFade(double dist) {
      double t = Mth.clamp((dist - 22.0) / 42.0, 0.0, 1.0);
      return (float)(1.0 - t * t * (3.0 - 2.0 * t));
   }

   private static double falloff(double t) {
      double k = t / 28.0;
      return 1.0 / (1.0 + k * k);
   }

   private static double bilerp(double[] v, double a, double c) {
      return (1.0 - a) * (1.0 - c) * v[0] + a * (1.0 - c) * v[1] + a * c * v[2] + (1.0 - a) * c * v[3];
   }

   private static double bilerp(float[] v, double a, double c) {
      return (1.0 - a) * (1.0 - c) * v[0] + a * (1.0 - c) * v[1] + a * c * v[2] + (1.0 - a) * c * v[3];
   }

   private static final class Beam {
      Player owner;
      Vec3 origin;
      Vec3 dir;
      Vec3 u;
      Vec3 v;
      Vec3 shadowTarget;
      Vec3 hit;
      boolean hitBlock;
      double nearest;
   }

   private record Candidate(BlockState state, BlockPos pos, Vec3 offset, BakedQuad quad) {
   }

   private record LightBeam(Entity owner, Vec3 origin, Vec3 dir, Vec3 u, Vec3 v, double tan, float brightness) {
   }
}
