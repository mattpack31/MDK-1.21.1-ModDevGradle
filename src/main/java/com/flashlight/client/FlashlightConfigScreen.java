package com.flashlight.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

public class FlashlightConfigScreen extends Screen {
   private static final int WIDTH = 220;
   private static final int ROW = 24;
   @Nullable
   private final Screen parent;
   private final FlashlightConfig config = FlashlightConfig.get();
   private AbstractSliderButton rate;

   public FlashlightConfigScreen(@Nullable Screen parent) {
      super(Component.translatable("flashlight.config.title"));
      this.parent = parent;
   }

   protected void init() {
      int x = (this.width - 220) / 2;
      int y = this.height / 4;
      this.addRenderableWidget(new FlashlightConfigScreen.IntSlider(x, y, 10, 60, this.config.beamAngle, "flashlight.config.beam_size") {
         @Override
         protected void set(int value) {
            FlashlightConfigScreen.this.config.beamAngle = value;
         }

         @Override
         protected Component label(int value) {
            return Component.translatable("flashlight.config.beam_size.value", new Object[]{value * 2});
         }
      });
      this.addRenderableWidget(
         CycleButton.onOffBuilder(this.config.flicker).create(x, y + 24, 220, 20, Component.translatable("flashlight.config.flicker"), (button, on) -> {
            this.config.flicker = on;
            this.rate.active = on;
         })
      );
      this.rate = (AbstractSliderButton)this.addRenderableWidget(
         new FlashlightConfigScreen.IntSlider(x, y + 48, 1, 30, this.config.flickersPerMinute, "flashlight.config.flicker_rate") {
            @Override
            protected void set(int value) {
               FlashlightConfigScreen.this.config.flickersPerMinute = value;
            }

            @Override
            protected Component label(int value) {
               return Component.translatable("flashlight.config.flicker_rate.value", new Object[]{value});
            }
         }
      );
      this.rate.active = this.config.flicker;
      this.addRenderableWidget(
         CycleButton.<Integer>builder(q -> Component.translatable("flashlight.config.shadows." + q))
            .withValues(0, 1, 2)
            .withInitialValue(this.config.shadowQuality)
            .create(x, y + 72, 220, 20, Component.translatable("flashlight.config.shadows"), (button, q) -> this.config.shadowQuality = q)
      );
      this.addRenderableWidget(
         CycleButton.onOffBuilder(this.config.dust)
            .create(x, y + 96, 220, 20, Component.translatable("flashlight.config.dust"), (button, on) -> this.config.dust = on)
      );
      this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).bounds(x, y + 144, 220, 20).build());
   }

   public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
      // En 1.21.x Screen.render ya dibuja el fondo; llamar a renderBackground aparte lo dibujaria dos veces (crash por blur doble).
      super.render(context, mouseX, mouseY, delta);
      context.drawCenteredString(this.font, this.title, this.width / 2, this.height / 4 - 24, 16777215);
   }

   public void onClose() {
      this.config.save();
      if (this.minecraft != null) {
         this.minecraft.setScreen(this.parent);
      }
   }

   private abstract static class IntSlider extends AbstractSliderButton {
      private final int min;
      private final int max;
      private final String key;

      IntSlider(int x, int y, int min, int max, int value, String key) {
         super(x, y, 220, 20, Component.empty(), (double)(value - min) / (max - min));
         this.min = min;
         this.max = max;
         this.key = key;
         this.updateMessage();
      }

      private int current() {
         return this.min + (int)Math.round(this.value * (this.max - this.min));
      }

      protected abstract void set(int var1);

      protected abstract Component label(int var1);

      protected void updateMessage() {
         this.setMessage(Component.translatable(this.key).append(": ").append(this.label(this.current())));
      }

      protected void applyValue() {
         this.set(Mth.clamp(this.current(), this.min, this.max));
      }
   }
}
