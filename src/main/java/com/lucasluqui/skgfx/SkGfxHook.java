package com.lucasluqui.skgfx;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * The static methods the rewritten LWJGL call sites land on. Everything runs on the game's render thread with its GL
 * context current. Any failure permanently disables the pipeline and falls back to the real calls: the game must
 * always keep running, just without enhancements.
 */
public final class SkGfxHook
{
  static final float SCALE = clamp(floatProp("skgfx.scale", 1f), 0.25f, 4f);
  static final float SHARPEN = clamp(floatProp("skgfx.sharpen", 0f), 0f, 1f);
  static final boolean DEBUG_TINT = Boolean.getBoolean("skgfx.debugTint");

  /** Whether the offscreen pipeline is wanted at all; a failure turns this off for the rest of the run. */
  private static volatile boolean active = SCALE != 1f || SHARPEN > 0f || DEBUG_TINT;

  private static SkGfxPipeline pipeline;

  public static void swapBuffers (long window)
  {
    if (active) {
      try {
        if (pipeline == null) pipeline = new SkGfxPipeline();
        pipeline.present(window);
      } catch (Throwable t) {
        disable(t);
      }
    }
    GLFW.glfwSwapBuffers(window);
    if (active) {
      try {
        pipeline.afterSwap(window);
      } catch (Throwable t) {
        disable(t);
      }
    }
  }

  /** Whether the game believes framebuffer 0 (the window) is bound. Only touched on the render thread. */
  private static boolean onDefaultFramebuffer = true;

  /** The game binds framebuffer 0 to draw to the screen: give it the offscreen framebuffer instead. */
  public static void bindFramebuffer (int target, int framebuffer)
  {
    onDefaultFramebuffer = framebuffer == 0;
    if (active && framebuffer == 0 && pipeline != null) {
      framebuffer = pipeline.framebuffer();
    }
    GL30.glBindFramebuffer(target, framebuffer);
  }

  /**
   * The game sizes its viewport for the window; rasterize into the scaled target instead. Its own offscreen passes
   * (bound to its own framebuffers) keep their real sizes.
   */
  public static void viewport (int x, int y, int width, int height)
  {
    if (active && onDefaultFramebuffer) {
      GL11.glViewport(scaledPos(x), scaledPos(y), scaled(width), scaled(height));
    } else {
      GL11.glViewport(x, y, width, height);
    }
  }

  public static void scissor (int x, int y, int width, int height)
  {
    if (active && onDefaultFramebuffer) {
      GL11.glScissor(scaledPos(x), scaledPos(y), scaled(width), scaled(height));
    } else {
      GL11.glScissor(x, y, width, height);
    }
  }

  static int scaled (int size)
  {
    return Math.max(1, Math.round(size * SCALE));
  }

  private static int scaledPos (int position)
  {
    return Math.round(position * SCALE);
  }

  private static void disable (Throwable t)
  {
    active = false;
    System.err.println("[skgfx] disabled after error: " + t);
    t.printStackTrace();
    if (pipeline != null) {
      pipeline.dispose();
      pipeline = null;
    }
  }

  private static float floatProp (String key, float fallback)
  {
    try {
      String value = System.getProperty(key);
      return value == null ? fallback : Float.parseFloat(value);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static float clamp (float value, float min, float max)
  {
    return Math.min(max, Math.max(min, value));
  }

  private SkGfxHook () { }
}
