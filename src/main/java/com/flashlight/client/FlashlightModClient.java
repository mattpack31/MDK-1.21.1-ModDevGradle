package com.flashlight.client;

import com.flashlight.FlashlightMod;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Punto de entrada SOLO del cliente (dist = CLIENT): el servidor dedicado nunca carga esta clase. */
@Mod(value = FlashlightMod.MOD_ID, dist = Dist.CLIENT)
public class FlashlightModClient {
   public static final Logger LOGGER = LoggerFactory.getLogger("flashlight");
   private static final Set<UUID> LIT = new HashSet<>();
   private static KeyMapping toggleKey;
   private static KeyMapping settingsKey;

   public FlashlightModClient(IEventBus modBus, ModContainer container) {
      toggleKey = new KeyMapping("key.flashlight.toggle", InputConstants.Type.KEYSYM, 70, "key.categories.flashlight");
      settingsKey = new KeyMapping("key.flashlight.settings", InputConstants.Type.KEYSYM, 74, "key.categories.flashlight");

      modBus.addListener((RegisterKeyMappingsEvent event) -> {
         event.register(toggleKey);
         event.register(settingsKey);
      });
      modBus.addListener((RegisterShadersEvent event) -> {
         try {
            event.registerShader(
               new ShaderInstance(event.getResourceProvider(), FlashlightMod.id("beam_light"), DefaultVertexFormat.POSITION_TEX_COLOR),
               program -> BeamRenderer.shader = program
            );
         } catch (IOException e) {
            LOGGER.error("Couldn't load the flashlight shader", e);
         }
      });
      modBus.addListener((RegisterParticleProvidersEvent event) ->
         event.registerSpriteSet(FlashlightMod.DUST.get(), DustParticle.Factory::new));

      NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre event) -> handleKeys(Minecraft.getInstance()));
      NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> BeamRenderer.tickLights(Minecraft.getInstance()));
      NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
         Minecraft client = Minecraft.getInstance();
         if (client.level != null) {
            float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
               BeamRenderer.prepareLights(client.level, event.getCamera(), partial);
            } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
               BeamRenderer.render(client.level, event.getCamera(), partial, event.getPoseStack());
            }
         }
      });
      NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> LIT.clear());

      container.registerExtensionPoint(IConfigScreenFactory.class, (mc, parent) -> new FlashlightConfigScreen(parent));
   }

   public static boolean isLit(Player player) {
      return LIT.contains(player.getUUID());
   }

   public static void setLit(UUID id, boolean on) {
      if (on) {
         LIT.add(id);
      } else {
         LIT.remove(id);
      }
   }

   public static void requestToggle() {
      ClientPacketListener handler = Minecraft.getInstance().getConnection();
      // Solo enviar si el servidor tiene el mod (equivale a isRemotePresent en Forge).
      if (handler != null && handler.hasChannel(FlashlightMod.Toggle.TYPE)) {
         PacketDistributor.sendToServer(new FlashlightMod.Toggle());
      }
   }

   private static void handleKeys(Minecraft client) {
      int presses = 0;

      while (toggleKey.consumeClick()) {
         presses++;
      }

      // Si F tambien esta asignada a "cambiar mano", la linterna tiene prioridad.
      if (toggleKey.same(client.options.keySwapOffhand)) {
         int swaps = 0;

         while (client.options.keySwapOffhand.consumeClick()) {
            swaps++;
         }

         presses = Math.max(presses, swaps);
      }

      for (int i = 0; i < presses; i++) {
         requestToggle();
      }

      while (settingsKey.consumeClick()) {
         if (client.screen == null) {
            client.setScreen(new FlashlightConfigScreen(null));
         }
      }
   }
}
