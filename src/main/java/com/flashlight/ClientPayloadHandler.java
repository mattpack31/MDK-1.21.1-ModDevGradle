package com.flashlight;

import com.flashlight.client.FlashlightModClient;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Manejador de paquetes que solo se ejecuta en el cliente. Esta clase NO importa clases de cliente
 * a nivel de archivo (solo FlashlightModClient dentro del metodo), asi el servidor dedicado no la rompe.
 */
public final class ClientPayloadHandler {
   private ClientPayloadHandler() {
   }

   public static void handleState(FlashlightMod.State msg, IPayloadContext ctx) {
      FlashlightModClient.setLit(msg.id(), msg.on());
   }
}
