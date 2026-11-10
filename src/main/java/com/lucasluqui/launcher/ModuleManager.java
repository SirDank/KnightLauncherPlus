package com.lucasluqui.launcher;

import com.lucasluqui.launcher.setting.SettingsManager;
import com.lucasluqui.util.FileUtil;
import com.lucasluqui.util.SystemUtil;
import com.lucasluqui.util.ZipUtil;
import com.lucasluqui.util.ThreadingUtil;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import java.io.IOException;

import static com.lucasluqui.launcher.Log.log;

@Singleton
public class ModuleManager
{
  @Inject protected SettingsManager _settingsManager;

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

  public void loadSpiralview ()
  {
    try {
      ZipUtil.extractFileWithinJar("/rsrc/modules/spiralview/spiralview.jar",
        LauncherGlobals.USER_DIR + "/KnightLauncher/modules/spiralview/spiralview.jar");
    } catch (IOException e) {
      log.error(e);
    }
  }

  public void loadTortodrone ()
  {
    try {
      java.io.File serverDir = new java.io.File(LauncherGlobals.USER_DIR, "KnightLauncher/server");
      java.io.File jarTarget = new java.io.File(serverDir, "tortodrone.jar");
      ZipUtil.extractFileWithinJar("/rsrc/modules/tortodrone/tortodrone.jar", jarTarget.getAbsolutePath());

      java.io.File contentZip = new java.io.File(serverDir, "content.zip");
      if (!new java.io.File(serverDir, "content").isDirectory() || !new java.io.File(serverDir, "config").isDirectory()) {
        ZipUtil.extractFileWithinJar("/rsrc/modules/tortodrone/content.zip", contentZip.getAbsolutePath());
        ZipUtil.unzip(contentZip.getAbsolutePath(), serverDir.getAbsolutePath() + java.io.File.separator);
        FileUtil.deleteFile(contentZip.getAbsolutePath());
      }
    } catch (IOException e) {
      log.error("Could not extract Tortodrone server module", e);
    }
  }

}
