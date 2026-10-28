package com.flashlight.client;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

final class GhostLights {
   private static final int FLAGS = 18;
   private static final Map<BlockPos, BlockState> PLACED = new HashMap<>();
   private static ClientLevel placedIn;

   private GhostLights() {
   }

   static void update(ClientLevel world, Map<BlockPos, Integer> wanted) {
      if (world != placedIn) {
         PLACED.clear();
         placedIn = world;
      }

      Iterator<Entry<BlockPos, BlockState>> it = PLACED.entrySet().iterator();

      while (it.hasNext()) {
         Entry<BlockPos, BlockState> e = it.next();
         if (!wanted.containsKey(e.getKey())) {
            restore(world, e.getKey(), e.getValue());
            it.remove();
         }
      }

      wanted.forEach((pos, level) -> place(world, pos, level));
   }

   static void clear(ClientLevel world) {
      if (world == placedIn) {
         PLACED.forEach((pos, previous) -> restore(world, pos, previous));
      }

      PLACED.clear();
   }

   private static void place(ClientLevel world, BlockPos pos, int level) {
      if (world.hasChunkAt(pos)) {
         BlockState current = world.getBlockState(pos);
         if (PLACED.containsKey(pos)) {
            if (current.is(Blocks.LIGHT)) {
               if ((Integer)current.getValue(LightBlock.LEVEL) != level) {
                  world.setBlock(pos, (BlockState)current.setValue(LightBlock.LEVEL, level), 18);
               }

               return;
            }

            PLACED.remove(pos);
         }

         BlockState light = (BlockState)Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, level);
         if (current.isAir()) {
            PLACED.put(pos.immutable(), current);
            world.setBlock(pos, light, 18);
         } else if (current.is(Blocks.WATER) && current.getFluidState().isSource()) {
            PLACED.put(pos.immutable(), current);
            world.setBlock(pos, (BlockState)light.setValue(BlockStateProperties.WATERLOGGED, true), 18);
         }
      }
   }

   private static void restore(ClientLevel world, BlockPos pos, BlockState previous) {
      if (world.hasChunkAt(pos) && world.getBlockState(pos).is(Blocks.LIGHT)) {
         world.setBlock(pos, previous, 18);
      }
   }
}
