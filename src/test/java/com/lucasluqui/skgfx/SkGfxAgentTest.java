package com.lucasluqui.skgfx;

import com.lucasluqui.launcher.SkGfxManager;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static java.lang.classfile.ClassFile.ACC_PUBLIC;
import static java.lang.classfile.ClassFile.ACC_STATIC;
import static org.junit.jupiter.api.Assertions.*;

public class SkGfxAgentTest
{
  /** Builds a class whose one method calls the LWJGL methods the agent intercepts, plus one it must leave alone. */
  private static byte[] fakeGameClass ()
  {
    ClassDesc glfw = ClassDesc.ofInternalName("org/lwjgl/glfw/GLFW");
    ClassDesc gl11 = ClassDesc.ofInternalName("org/lwjgl/opengl/GL11");
    ClassDesc gl30 = ClassDesc.ofInternalName("org/lwjgl/opengl/GL30");
    return ClassFile.of().build(ClassDesc.of("fake.Game"), cb -> cb
      .withMethodBody("render", MethodTypeDesc.ofDescriptor("(J)V"), ACC_PUBLIC | ACC_STATIC, code -> code
        .lload(0)
        .invokestatic(glfw, "glfwSwapBuffers", MethodTypeDesc.ofDescriptor("(J)V"))
        .iconst_0().iconst_0().iconst_1().iconst_1()
        .invokestatic(gl11, "glViewport", MethodTypeDesc.ofDescriptor("(IIII)V"))
        .iconst_0().iconst_0().iconst_1().iconst_1()
        .invokestatic(gl11, "glScissor", MethodTypeDesc.ofDescriptor("(IIII)V"))
        .iconst_0().iconst_0()
        .invokestatic(gl30, "glBindFramebuffer", MethodTypeDesc.ofDescriptor("(II)V"))
        .iconst_0().iconst_0()
        // Same name, different owner: must not be rewritten.
        .invokestatic(gl11, "glBindTexture", MethodTypeDesc.ofDescriptor("(II)V"))
        .return_()));
  }

  private static List<String> invokedOwners (byte[] bytes)
  {
    List<String> owners = new ArrayList<>();
    ClassFile.of().parse(bytes).methods().forEach(m -> m.code().ifPresent(code -> code.forEach(e -> {
      if (e instanceof InvokeInstruction i) {
        owners.add(i.owner().asInternalName() + "." + i.name().stringValue());
      }
    })));
    return owners;
  }

  @Test
  public void rewritesInterceptedCallSitesOnly ()
  {
    List<String> owners = invokedOwners(SkGfxAgent.rewrite(fakeGameClass()));
    String hook = "com/lucasluqui/skgfx/SkGfxHook";
    assertTrue(owners.contains(hook + ".swapBuffers"));
    assertTrue(owners.contains(hook + ".viewport"));
    assertTrue(owners.contains(hook + ".scissor"));
    assertTrue(owners.contains(hook + ".bindFramebuffer"));
    assertTrue(owners.contains("org/lwjgl/opengl/GL11.glBindTexture"), "untouched call rewritten: " + owners);
    assertFalse(owners.stream().anyMatch(o -> o.equals("org/lwjgl/glfw/GLFW.glfwSwapBuffers")));
  }

  @Test
  public void leavesUnrelatedClassesAlone ()
  {
    byte[] plain = ClassFile.of().build(ClassDesc.of("fake.Plain"), cb -> cb
      .withMethodBody("noop", MethodTypeDesc.ofDescriptor("()V"), ACC_PUBLIC | ACC_STATIC,
        code -> code.return_()));
    assertFalse(SkGfxAgent.mentionsInterceptedOwner(plain));
    assertNull(SkGfxAgent.rewrite(plain));
    // A class that mentions GL11 but only calls untouched methods is also left alone.
    byte[] textureOnly = ClassFile.of().build(ClassDesc.of("fake.Textures"), cb -> cb
      .withMethodBody("bind", MethodTypeDesc.ofDescriptor("()V"), ACC_PUBLIC | ACC_STATIC, code -> code
        .iconst_0().iconst_0()
        .invokestatic(ClassDesc.ofInternalName("org/lwjgl/opengl/GL11"), "glBindTexture",
          MethodTypeDesc.ofDescriptor("(II)V"))
        .return_()));
    assertTrue(SkGfxAgent.mentionsInterceptedOwner(textureOnly));
    assertNull(SkGfxAgent.rewrite(textureOnly));
    assertTrue(SkGfxAgent.mentionsInterceptedOwner(fakeGameClass()));
  }

  @Test
  public void extraTxtLinesUseRootLocaleAndRelativePath ()
  {
    List<String> lines = SkGfxManager.extraTxtLines(150, 40);
    assertEquals("-javaagent:KnightLauncher/modules/skgfx/skgfx-agent.jar", lines.get(0));
    assertEquals("-Dskgfx.scale=1.50", lines.get(1));
    assertEquals("-Dskgfx.sharpen=0.40", lines.get(2));
    // extra.txt args must survive the launcher's no-spaces validation.
    lines.forEach(line -> assertFalse(line.contains(" "), line));
  }

  @Test
  public void releaseFileDecidesAgentSupport ()
    throws Exception
  {
    File dir = Files.createTempDirectory("skgfx").toFile();
    File release = new File(dir, "release");
    Files.writeString(release.toPath(),
      "JAVA_VERSION=\"25.0.1\"\nMODULES=\"java.base java.desktop java.logging\"\n");
    assertFalse(SkGfxManager.releaseHasInstrument(release));
    Files.writeString(release.toPath(),
      "JAVA_VERSION=\"25.0.1\"\nMODULES=\"java.base java.instrument java.desktop\"\n");
    assertTrue(SkGfxManager.releaseHasInstrument(release));
    assertFalse(SkGfxManager.releaseHasInstrument(new File(dir, "missing")));
  }
}
