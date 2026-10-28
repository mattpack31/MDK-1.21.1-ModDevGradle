package com.flashlight.client;

import java.util.UUID;

final class Flicker {
   private Flicker() {
   }

   static float brightness(UUID player, double seconds) {
      FlashlightConfig config = FlashlightConfig.get();
      if (!config.flicker) {
         return 1.0F;
      }

      double slot = 60.0 / config.flickersPerMinute;
      long index = (long)Math.floor(seconds / slot);
      long seed = player.getLeastSignificantBits() ^ player.getMostSignificantBits();

      for (long i = index - 1L; i <= index; i++) {
         long h = mix(seed + i * -7046029254386353131L);
         double length = 0.15 + unit(h >>> 8) * 0.55;
         double start = i * slot + unit(h >>> 24) * Math.max(0.0, slot - length);
         double into = seconds - start;
         if (!(into < 0.0) && !(into >= length)) {
            int step = (int)(into / 0.05);
            double level = unit(mix(h + step * 31L) >>> 16);
            if (level < 0.35) {
               return 0.04F;
            }

            if (level < 0.7) {
               return 0.35F + (float)level * 0.4F;
            }

            return 1.0F;
         }
      }

      return 1.0F;
   }

   private static long mix(long z) {
      z = (z ^ z >>> 30) * -4658895280553007687L;
      z = (z ^ z >>> 27) * -7723592293110705685L;
      return z ^ z >>> 31;
   }

   private static double unit(long bits) {
      return (bits & 16777215L) / 1.6777216E7;
   }
}
