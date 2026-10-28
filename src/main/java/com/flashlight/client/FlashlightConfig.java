package com.flashlight.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.util.Mth;
import net.neoforged.fml.loading.FMLPaths;

public final class FlashlightConfig {
   public static final int MIN_BEAM_ANGLE = 10;
   public static final int MAX_BEAM_ANGLE = 60;
   public static final int MIN_FLICKERS_PER_MINUTE = 1;
   public static final int MAX_FLICKERS_PER_MINUTE = 30;
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("flashlight.json");
   private static FlashlightConfig current;
   public int beamAngle = 30;
   public boolean flicker = false;
   public int flickersPerMinute = 4;
   public boolean dust = true;
   public int shadowQuality = 2;

   public static FlashlightConfig get() {
      if (current == null) {
         current = load();
      }

      return current;
   }

   public int shadowMapSize() {
      return switch (this.shadowQuality) {
         case 0 -> 0;
         case 1 -> 40;
         default -> 64;
      };
   }

   public double tanBeam() {
      return Math.tan(Math.toRadians(this.beamAngle));
   }

   public void save() {
      this.clamp();

      try {
         Files.createDirectories(FILE.getParent());

         try (Writer out = Files.newBufferedWriter(FILE)) {
            GSON.toJson(this, out);
         }
      } catch (IOException e) {
         FlashlightModClient.LOGGER.warn("Couldn't save {}", FILE, e);
      }
   }

   private static FlashlightConfig load() {
      FlashlightConfig config = null;
      if (Files.exists(FILE)) {
         try (Reader in = Files.newBufferedReader(FILE)) {
            config = (FlashlightConfig)GSON.fromJson(in, FlashlightConfig.class);
         } catch (IOException | JsonParseException e) {
            FlashlightModClient.LOGGER.warn("Couldn't read {}, using defaults", FILE, e);
         }
      }

      if (config == null) {
         config = new FlashlightConfig();
         config.save();
      }

      config.clamp();
      return config;
   }

   private void clamp() {
      this.beamAngle = Mth.clamp(this.beamAngle, 10, 60);
      this.flickersPerMinute = Mth.clamp(this.flickersPerMinute, 1, 30);
      this.shadowQuality = Mth.clamp(this.shadowQuality, 0, 2);
   }
}
