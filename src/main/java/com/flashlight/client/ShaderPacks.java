package com.flashlight.client;

import java.lang.reflect.Method;

final class ShaderPacks {
   private static boolean looked;
   private static Object api;
   private static Method inUse;

   private ShaderPacks() {
   }

   static boolean inUse() {
      if (!looked) {
         looked = true;

         try {
            Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            api = type.getMethod("getInstance").invoke(null);
            inUse = type.getMethod("isShaderPackInUse");
         } catch (ReflectiveOperationException | LinkageError e) {
            api = null;
         }
      }

      if (api == null) {
         return false;
      }

      try {
         return (Boolean)inUse.invoke(api);
      } catch (ReflectiveOperationException e) {
         return false;
      }
   }
}
