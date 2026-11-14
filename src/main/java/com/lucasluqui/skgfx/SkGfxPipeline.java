package com.lucasluqui.skgfx;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;

import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * The offscreen render target the game is steered into, and the full-screen pass that puts it on screen each frame:
 * bilinear scale plus optional contrast-adaptive sharpening (CAS). Written for the game's own context: GL 2.1
 * compatibility (GLSL 1.20, ARB-style framebuffer objects through the GL30 entry points the game itself uses).
 * <p>
 * Order per frame: the game draws into {@link #framebuffer()} (via the bind redirect), {@link #present(long)}
 * composites it onto the window just before the real swap, and {@link #afterSwap(long)} rebinds the offscreen target
 * so the next frame lands there too. Known limits, documented rather than handled: the game's own MSAA setting does
 * not multisample the offscreen target, and the HUD is scaled with the scene (the game's UI-scale option compensates).
 */
final class SkGfxPipeline
{
  private final IntBuffer widthBuf = BufferUtils.createIntBuffer(1);
  private final IntBuffer heightBuf = BufferUtils.createIntBuffer(1);

  private int realWidth, realHeight;
  private int framebuffer, colorTexture, depthStencil, program;
  private int texelUniform, sharpenUniform;
  private boolean hasFrame;

  int framebuffer ()
  {
    return framebuffer;
  }

  /** Composites the offscreen frame onto the window. Call right before the real swap. */
  void present (long window)
  {
    boolean fresh = ensure(window);
    if (fresh || !hasFrame) {
      // Nothing of ours was rendered this frame; leave the window as the game drew it.
      return;
    }

    int savedProgram = glGetInteger(GL_CURRENT_PROGRAM);
    glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_TEXTURE_BIT
      | GL_CURRENT_BIT | GL_VIEWPORT_BIT | GL_SCISSOR_BIT | GL_STENCIL_BUFFER_BIT);
    try {
      glBindFramebuffer(GL_FRAMEBUFFER, 0);
      glDisable(GL_DEPTH_TEST);
      glDisable(GL_STENCIL_TEST);
      glDisable(GL_SCISSOR_TEST);
      glDisable(GL_BLEND);
      glDisable(GL_ALPHA_TEST);
      glColorMask(true, true, true, true);
      glViewport(0, 0, realWidth, realHeight);

      glUseProgram(program);
      glUniform2f(texelUniform, 1f / SkGfxHook.scaled(realWidth), 1f / SkGfxHook.scaled(realHeight));
      glUniform1f(sharpenUniform, SkGfxHook.SHARPEN);
      glActiveTexture(GL_TEXTURE0);
      glBindTexture(GL_TEXTURE_2D, colorTexture);

      glBegin(GL_QUADS);
      glTexCoord2f(0f, 0f); glVertex2f(-1f, -1f);
      glTexCoord2f(1f, 0f); glVertex2f(1f, -1f);
      glTexCoord2f(1f, 1f); glVertex2f(1f, 1f);
      glTexCoord2f(0f, 1f); glVertex2f(-1f, 1f);
      glEnd();

      if (SkGfxHook.DEBUG_TINT) {
        glEnable(GL_SCISSOR_TEST);
        glScissor(10, 10, 60, 60);
        glClearColor(0.67f, 0.29f, 0.51f, 1f);
        glClear(GL_COLOR_BUFFER_BIT);
      }
    } finally {
      glUseProgram(savedProgram);
      glPopAttrib();
    }
  }

  /** The swap left the window bound; point the game back at the offscreen target for its next frame. */
  void afterSwap (long window)
  {
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    hasFrame = true;
  }

  /**
   * Creates or resizes the offscreen target to match the window. @return true when (re)created this call, meaning the
   * game hasn't rendered into it yet.
   */
  private boolean ensure (long window)
  {
    GLFW.glfwGetFramebufferSize(window, widthBuf, heightBuf);
    int width = Math.max(1, widthBuf.get(0));
    int height = Math.max(1, heightBuf.get(0));
    if (framebuffer != 0 && width == realWidth && height == realHeight) {
      return false;
    }
    dispose();
    realWidth = width;
    realHeight = height;
    int scaledWidth = SkGfxHook.scaled(width);
    int scaledHeight = SkGfxHook.scaled(height);

    // The game caches GL state; put the texture binding back when done (renderbuffer is rebound to 0 below).
    int savedTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
    colorTexture = glGenTextures();
    glBindTexture(GL_TEXTURE_2D, colorTexture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, scaledWidth, scaledHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
    glBindTexture(GL_TEXTURE_2D, savedTexture);

    depthStencil = glGenRenderbuffers();
    glBindRenderbuffer(GL_RENDERBUFFER, depthStencil);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, scaledWidth, scaledHeight);
    glBindRenderbuffer(GL_RENDERBUFFER, 0);

    framebuffer = glGenFramebuffers();
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthStencil);
    int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
      throw new IllegalStateException("offscreen framebuffer incomplete: 0x" + Integer.toHexString(status));
    }

    if (program == 0) {
      program = buildProgram();
      texelUniform = glGetUniformLocation(program, "uTexel");
      sharpenUniform = glGetUniformLocation(program, "uSharp");
      int savedProgram = glGetInteger(GL_CURRENT_PROGRAM);
      glUseProgram(program);
      glUniform1i(glGetUniformLocation(program, "uTex"), 0);
      glUseProgram(savedProgram);
    }

    hasFrame = false;
    System.err.println("[skgfx] target " + scaledWidth + "x" + scaledHeight + " for window " + width + "x" + height);
    return true;
  }

  void dispose ()
  {
    if (framebuffer != 0) { glDeleteFramebuffers(framebuffer); framebuffer = 0; }
    if (colorTexture != 0) { glDeleteTextures(colorTexture); colorTexture = 0; }
    if (depthStencil != 0) { glDeleteRenderbuffers(depthStencil); depthStencil = 0; }
    hasFrame = false;
  }

  private static int buildProgram ()
  {
    int vertex = compile(GL_VERTEX_SHADER, """
      #version 120
      void main() {
        gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0);
        gl_TexCoord[0] = gl_MultiTexCoord0;
      }
      """);
    // AMD-style contrast-adaptive sharpening; uSharp 0 is a plain bilinear fetch.
    int fragment = compile(GL_FRAGMENT_SHADER, """
      #version 120
      uniform sampler2D uTex;
      uniform vec2 uTexel;
      uniform float uSharp;
      void main() {
        vec2 tc = gl_TexCoord[0].st;
        vec3 e = texture2D(uTex, tc).rgb;
        if (uSharp <= 0.0) {
          gl_FragColor = vec4(e, 1.0);
          return;
        }
        vec3 b = texture2D(uTex, tc + vec2(0.0, -uTexel.y)).rgb;
        vec3 d = texture2D(uTex, tc + vec2(-uTexel.x, 0.0)).rgb;
        vec3 f = texture2D(uTex, tc + vec2(uTexel.x, 0.0)).rgb;
        vec3 h = texture2D(uTex, tc + vec2(0.0, uTexel.y)).rgb;
        vec3 mn = min(min(min(d, e), min(f, b)), h);
        vec3 mx = max(max(max(d, e), max(f, b)), h);
        vec3 amp = clamp(min(mn, 2.0 - mx) / (mx + 1e-5), 0.0, 1.0);
        amp = sqrt(amp);
        float peak = -1.0 / mix(8.0, 5.0, uSharp);
        vec3 w = amp * peak;
        gl_FragColor = vec4(((b + d + f + h) * w + e) / (4.0 * w + 1.0), 1.0);
      }
      """);
    int program = glCreateProgram();
    glAttachShader(program, vertex);
    glAttachShader(program, fragment);
    glLinkProgram(program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
      throw new IllegalStateException("shader link failed: " + glGetProgramInfoLog(program));
    }
    return program;
  }

  private static int compile (int type, String source)
  {
    int shader = glCreateShader(type);
    glShaderSource(shader, source);
    glCompileShader(shader);
    if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE) {
      throw new IllegalStateException("shader compile failed: " + glGetShaderInfoLog(shader));
    }
    return shader;
  }
}
