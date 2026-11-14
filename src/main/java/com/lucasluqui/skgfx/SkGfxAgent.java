package com.lucasluqui.skgfx;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;

/**
 * Graphics-enhancement agent for the game ({@code -javaagent:...skgfx-agent.jar}). It never touches the game's own
 * classes by name: it rewrites call sites of a few stable LWJGL methods, so it keeps working across game updates.
 * <p>
 * Rewritten call sites (all single or few call sites in the game, and all on the render thread):
 * <ul>
 *   <li>{@code GLFW.glfwSwapBuffers(long)}: the per-frame hook where the scaled frame is presented.</li>
 *   <li>{@code GL30.glBindFramebuffer(int, int)}: a bind of framebuffer 0 lands on the offscreen framebuffer
 *       instead, so the game draws there without knowing.</li>
 *   <li>{@code GL11.glViewport/glScissor}: scaled while the game believes it is drawing to framebuffer 0, so the
 *       full-size layout it computes rasterizes into the scaled target. The game's believed sizes never change, so
 *       projection, UI layout and mouse picking stay exact; its own offscreen passes keep their real viewports.</li>
 * </ul>
 * Configured with system properties, written by the launcher next to the -javaagent line:
 * {@code skgfx.scale} (render scale, 0.25-4.0; above 1 supersamples, below 1 renders smaller and upscales),
 * {@code skgfx.sharpen} (0-1 contrast-adaptive sharpening), {@code skgfx.debugTint} (corner marker, for testing).
 */
public final class SkGfxAgent
{
  private static final String GLFW = "org/lwjgl/glfw/GLFW";
  private static final String GL11 = "org/lwjgl/opengl/GL11";
  private static final String GL30 = "org/lwjgl/opengl/GL30";
  private static final ClassDesc HOOK = ClassDesc.of("com.lucasluqui.skgfx.SkGfxHook");

  public static void premain (String args, Instrumentation inst)
    throws Exception
  {
    // The hook classes must be visible to the game's classes, wherever they were loaded from.
    String self = SkGfxAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
    inst.appendToSystemClassLoaderSearch(new JarFile(self));
    inst.addTransformer(new Transformer());
    System.err.println("[skgfx] agent active: scale=" + SkGfxHook.SCALE + " sharpen=" + SkGfxHook.SHARPEN);
  }

  private static final class Transformer implements ClassFileTransformer
  {
    @Override
    public byte[] transform (ClassLoader loader, String name, Class<?> cls, ProtectionDomain domain, byte[] bytes)
    {
      // Never touch ourselves, LWJGL itself (the hook calls the real methods) or the JDK.
      if (name == null || name.startsWith("com/lucasluqui/skgfx/") || name.startsWith("org/lwjgl/")
        || name.startsWith("java/") || name.startsWith("jdk/") || name.startsWith("sun/")) {
        return null;
      }
      // Cheap pre-filter: only classes whose constant pool can name an intercepted owner are worth parsing,
      // and only a class where a call site was actually replaced is handed back rewritten. Everything else
      // keeps its original bytes, so the agent leaves the rest of the game untouched.
      if (!mentionsInterceptedOwner(bytes)) return null;
      try {
        return rewrite(bytes);
      } catch (Throwable t) {
        // An unreadable class is left as it was; the game must keep launching no matter what.
        System.err.println("[skgfx] couldn't instrument " + name + ": " + t);
        return null;
      }
    }
  }

  /** Rewrites the intercepted LWJGL call sites in one class, or returns null when it has none. */
  static byte[] rewrite (byte[] bytes)
  {
    boolean[] hit = {false};
    CodeTransform swap = (builder, element) -> {
      String target = element instanceof InvokeInstruction call ? hookMethod(call) : null;
      if (target != null) {
        hit[0] = true;
        builder.invokestatic(HOOK, target, MethodTypeDesc.ofDescriptor(((InvokeInstruction) element).type().stringValue()));
      } else {
        builder.with(element);
      }
    };
    ClassFile cf = ClassFile.of();
    byte[] out = cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethodBodies(swap));
    return hit[0] ? out : null;
  }

  /** The SkGfxHook method replacing this call, or null for calls left alone. Hook signatures match the originals. */
  private static String hookMethod (InvokeInstruction call)
  {
    String owner = call.owner().asInternalName();
    String name = call.name().stringValue();
    String desc = call.type().stringValue();
    if (owner.equals(GLFW) && name.equals("glfwSwapBuffers") && desc.equals("(J)V")) return "swapBuffers";
    if (owner.equals(GL11) && name.equals("glViewport") && desc.equals("(IIII)V")) return "viewport";
    if (owner.equals(GL11) && name.equals("glScissor") && desc.equals("(IIII)V")) return "scissor";
    if (owner.equals(GL30) && name.equals("glBindFramebuffer") && desc.equals("(II)V")) return "bindFramebuffer";
    return null;
  }

  /** Scans raw class bytes for the UTF-8 constants naming the intercepted owners. */
  static boolean mentionsInterceptedOwner (byte[] bytes)
  {
    return contains(bytes, OWNER_GLFW) || contains(bytes, OWNER_GL11) || contains(bytes, OWNER_GL30);
  }

  private static final byte[] OWNER_GLFW = GLFW.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  private static final byte[] OWNER_GL11 = GL11.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  private static final byte[] OWNER_GL30 = GL30.getBytes(java.nio.charset.StandardCharsets.UTF_8);

  private static boolean contains (byte[] haystack, byte[] needle)
  {
    outer:
    for (int i = 0; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) continue outer;
      }
      return true;
    }
    return false;
  }

  private SkGfxAgent () { }
}
