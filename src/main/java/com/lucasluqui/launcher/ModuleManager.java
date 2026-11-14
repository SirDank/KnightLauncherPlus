package com.lucasluqui.launcher;

import com.lucasluqui.launcher.setting.SettingsManager;
import com.lucasluqui.util.FileUtil;
import com.lucasluqui.util.SystemUtil;
import com.lucasluqui.util.ZipUtil;
import com.lucasluqui.util.ThreadingUtil;

import com.google.inject.Inject;
import com.google.inject.Singleton;


import java.io.File;
import java.io.IOException;

import static com.lucasluqui.launcher.Log.log;

@Singleton
public class ModuleManager
{
  @Inject protected SettingsManager _settingsManager;
  @Inject protected SkGfxManager _skGfxManager;

  public ModuleManager ()
  {
    // empty.
  }

  public void init ()
  {
    // empty.
  }

  public void loadModules ()
  {
    ThreadingUtil.start("ModuleLoader", () -> {
      loadIngameRPC();
      loadExtraPerf();
      loadSkGfx();
      // A game update can silently bring back the stripped JVM that can't load the agent.
      _skGfxManager.ensureConsistent(com.lucasluqui.launcher.setting.Settings.gameGfxEnabled);
    });
  }

  protected void loadIngameRPC ()
  {
    if (SystemUtil.isWindows() && SystemUtil.is64Bit()) {
      try {
        ZipUtil.extractFileWithinJar("/rsrc/modules/skdiscordrpc/bundle.zip", LauncherGlobals.USER_DIR + "\\KnightLauncher\\modules\\skdiscordrpc\\bundle.zip");
        ZipUtil.unzip(LauncherGlobals.USER_DIR + "\\KnightLauncher\\modules\\skdiscordrpc\\bundle.zip", LauncherGlobals.USER_DIR + "\\KnightLauncher\\modules\\skdiscordrpc\\");
        FileUtil.deleteFile(LauncherGlobals.USER_DIR + "\\KnightLauncher\\modules\\skdiscordrpc\\bundle.zip");
        _settingsManager.setValue("launcher.ingameRPCSetup", "true");
      } catch (IOException e) {
        log.error(e);
      }
    } else {
      _settingsManager.setValue("launcher.ingameRPCSetup", "true");
      _settingsManager.setValue("launcher.useIngameRPC", "false");
    }
  }

  protected void loadExtraPerf ()
  {
    try {
      ZipUtil.extractFileWithinJar(
        "/rsrc/modules/extra-perf/extra-perf",
        LauncherGlobals.USER_DIR + "\\KnightLauncher\\modules\\extra-perf\\extra-perf"
      );
    } catch (IOException e) {
      log.error(e);
    }
  }

  /**
   * The graphics-enhancement javaagent the game can be launched with (render scale and sharpening, see
   * com.lucasluqui.skgfx). Always extracted so the jar named in extra.txt is in place and current.
   */
  protected void loadSkGfx ()
  {
    try {
      ZipUtil.extractFileWithinJar("/rsrc/modules/skgfx/skgfx-agent.jar",
        LauncherGlobals.USER_DIR + "/KnightLauncher/modules/skgfx/skgfx-agent.jar");
    } catch (IOException e) {
      log.error(e);
    }
  }

  public void loadSpiralview ()
  {
    try {
      ZipUtil.extractFileWithinJar("/rsrc/modules/spiralview/spiralview.jar",
        LauncherGlobals.USER_DIR + "/KnightLauncher/modules/spiralview/spiralview.jar");
    } catch (IOException e) {
      log.error(e);
    }
  }

}
