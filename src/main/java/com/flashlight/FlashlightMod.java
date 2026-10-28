package com.flashlight;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Lado comun (cliente + servidor dedicado). NO debe importar nada de net.minecraft.client.
 */
@Mod(FlashlightMod.MOD_ID)
public class FlashlightMod {
   public static final String MOD_ID = "flashlight";
   private static final String PROTOCOL = "1";

   private static final DeferredRegister<ParticleType<?>> PARTICLES = DeferredRegister.create(Registries.PARTICLE_TYPE, MOD_ID);
   public static final DeferredHolder<ParticleType<?>, SimpleParticleType> DUST = PARTICLES.register("dust", () -> new SimpleParticleType(false) {});

   /** Jugadores con la linterna encendida (solo el servidor lo usa como fuente de verdad). */
   private static final Set<UUID> LIT = new HashSet<>();

   public static ResourceLocation id(String path) {
      return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
   }

   public FlashlightMod(IEventBus modBus) {
      PARTICLES.register(modBus);
      modBus.addListener(FlashlightMod::registerPayloads);

      // Un jugador entra: enviarle quien tiene la linterna encendida.
      NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
         if (event.getEntity() instanceof ServerPlayer player) {
            for (UUID id : LIT) {
               PacketDistributor.sendToPlayer(player, new State(id, true));
            }
         }
      });
      // Un jugador sale: apagar su linterna para todos.
      NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
         if (event.getEntity() instanceof ServerPlayer player) {
            UUID id = player.getUUID();
            if (LIT.remove(id)) {
               broadcast(id, false);
            }
         }
      });
      NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> LIT.clear());
   }

   private static void registerPayloads(RegisterPayloadHandlersEvent event) {
      // optional(): el cliente puede entrar a un servidor sin el mod y viceversa (como acceptMissingOr en Forge).
      PayloadRegistrar registrar = event.registrar(PROTOCOL).optional();
      registrar.playToServer(Toggle.TYPE, Toggle.CODEC, (msg, ctx) -> {
         if (ctx.player() instanceof ServerPlayer player) {
            toggle(player.getServer(), player);
         }
      });
      registrar.playToClient(State.TYPE, State.CODEC, ClientPayloadHandler::handleState);
   }

   private static void toggle(MinecraftServer server, ServerPlayer player) {
      if (server != null && !player.isSpectator()) {
         UUID id = player.getUUID();
         boolean on = !LIT.contains(id);
         if (on) {
            LIT.add(id);
         } else {
            LIT.remove(id);
         }

         // TODO verificar: el original usaba la constante ofuscada SoundEvents.f_12088_ (no pude resolverla).
         // LEVER_CLICK es la suposicion mas razonable para un "click" de interruptor.
         player.level().playSound(null, player.getX(), player.getEyeY(), player.getZ(),
            SoundEvents.LEVER_CLICK, SoundSource.PLAYERS, 0.35F, on ? 1.9F : 1.6F);
         broadcast(id, on);
      }
   }

   private static void broadcast(UUID id, boolean on) {
      PacketDistributor.sendToAllPlayers(new State(id, on));
   }

   /** Cliente -> servidor: "alterna mi linterna". */
   public record Toggle() implements CustomPacketPayload {
      public static final Type<Toggle> TYPE = new Type<>(FlashlightMod.id("toggle"));
      public static final StreamCodec<FriendlyByteBuf, Toggle> CODEC = StreamCodec.unit(new Toggle());

      @Override
      public Type<? extends CustomPacketPayload> type() {
         return TYPE;
      }
   }

   /** Servidor -> cliente: estado de la linterna de un jugador. */
   public record State(UUID id, boolean on) implements CustomPacketPayload {
      public static final Type<State> TYPE = new Type<>(FlashlightMod.id("state"));
      public static final StreamCodec<FriendlyByteBuf, State> CODEC = StreamCodec.composite(
         UUIDUtil.STREAM_CODEC, State::id,
         ByteBufCodecs.BOOL, State::on,
         State::new
      );

      @Override
      public Type<? extends CustomPacketPayload> type() {
         return TYPE;
      }
   }
}
