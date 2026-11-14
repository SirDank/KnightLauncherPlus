package com.lucasluqui.launcher;

import com.google.inject.Singleton;
import com.lucasluqui.util.FileUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;

import static com.lucasluqui.launcher.Log.log;

/**
 * Support for the skgfx graphics-enhancement agent (see com.lucasluqui.skgfx): whether the game's JVM can load a
 * javaagent, and swapping the game's stripped bundled JVM ({@code java_vm/}) for a full one when it can't.
 * <p>
 * The game's own JVM is a jlink image without the {@code java.instrument} module, so {@code -javaagent} refuses to
 * load there. getdown re-downloads {@code java_vm} only when its version stops satisfying the getdown.txt
 * {@code java_version} requirement, so a swapped-in full JVM of the same feature version survives game updates. The
 * original is kept as {@code java_vm.stock} and swapped back when enhancements are turned off.
 */
@Singleton
public class SkGfxManager
{
  public static final String AGENT_PATH = "KnightLauncher/modules/skgfx/skgfx-agent.jar";

  private static final String JAVA_VM = "java_vm";
  private static final String JAVA_VM_STOCK = "java_vm.stock";

  /** Whether the game's bundled JVM can load a javaagent. */
  public boolean gameJvmSupportsAgent ()
  {
    return releaseHasInstrument(new File(LauncherGlobals.USER_DIR, JAVA_VM + "/release"));
  }

  /**
   * A full JVM of the right feature version to copy over the game's, trying the cheapest source first: the JVM the
   * launcher runs on, then the launcher's own downloaded runtime, then a fresh download from the launcher's runtime
   * CDN (a full Temurin JRE). @return its home directory, or null when none could be had.
   */
  private File findFullJvm ()
  {
    if (ModuleLayer.boot().findModule("java.instrument").isPresent()
      && Runtime.version().feature() == requiredFeatureVersion()) {
      return new File(System.getProperty("java.home"));
    }
    File installed = com.lucasluqui.launcher.runtime.RuntimeLocator.getInstalledRuntimeDir();
    if (installed != null && releaseHasInstrument(new File(installed, "release"))) {
      return installed;
    }
    try {
      com.lucasluqui.launcher.runtime.RuntimeSource source =
        com.lucasluqui.launcher.runtime.RuntimeSource.forCurrentPlatform();
      if (source == null) return null;
      log.info("Downloading a full Java runtime for the game's enhancements");
      File downloaded = com.lucasluqui.launcher.runtime.RuntimeInstaller.install(source, null, null);
      return releaseHasInstrument(new File(downloaded, "release")) ? downloaded : null;
    } catch (IOException e) {
      log.error(e);
      return null;
    }
  }

  /**
   * Replaces the game's {@code java_vm} with a copy of a full runtime, keeping the original as
   * {@code java_vm.stock}. @return true when the game's JVM supports the agent afterwards.
   */
  public boolean installFullJvm ()
  {
    if (gameJvmSupportsAgent()) return true;
    File source = findFullJvm();
    if (source == null) {
      log.warning("No full JVM available to replace the game's", "required", requiredFeatureVersion());
      return false;
    }
    File gameDir = new File(LauncherGlobals.USER_DIR);
    File javaVm = new File(gameDir, JAVA_VM);
    File stock = new File(gameDir, JAVA_VM_STOCK);
    try {
      if (javaVm.exists() && !stock.exists() && !javaVm.renameTo(stock)) {
        log.warning("Couldn't back up the game's JVM", "path", javaVm);
        return false;
      }
      if (javaVm.exists()) deleteTree(javaVm);
      copyTree(source.toPath(), javaVm.toPath());
      boolean ok = gameJvmSupportsAgent();
      log.info("Installed full JVM for the game", "from", source, "ok", ok);
      if (!ok) restoreStockJvm();
      return ok;
    } catch (IOException e) {
      log.error(e);
      restoreStockJvm();
      return false;
    }
  }

  /**
   * Puts things right for the current state: a game update can silently bring the stripped JVM back, and extra.txt is
   * also read by launches that don't go through this launcher (Steam directly), so a stale -javaagent line on a JVM
   * that can't load it would stop the game from starting at all.
   */
  public void ensureConsistent (boolean enhancementsEnabled)
  {
    if (!enhancementsEnabled) return;
    if (!gameJvmSupportsAgent()) {
      log.info("Enhancements are on but the game's JVM lost javaagent support; reinstalling");
      if (!installFullJvm()) stripExtraTxt();
    }
  }

  /** Removes the agent's lines from extra.txt, leaving everything else as it was. */
  public void stripExtraTxt ()
  {
    File extra = new File(LauncherGlobals.USER_DIR, "extra.txt");
    if (!extra.isFile()) return;
    try {
      List<String> lines = Files.readAllLines(extra.toPath());
      List<String> kept = lines.stream()
        .filter(line -> !line.startsWith("-javaagent:" + AGENT_PATH) && !line.startsWith("-Dskgfx."))
        .toList();
      if (kept.size() != lines.size()) {
        Files.write(extra.toPath(), kept);
        log.info("Removed the agent's lines from extra.txt");
      }
    } catch (IOException e) {
      log.error(e);
    }
  }

  /** Puts the game's original JVM back, if a backup exists. */
  public void restoreStockJvm ()
  {
    File gameDir = new File(LauncherGlobals.USER_DIR);
    File javaVm = new File(gameDir, JAVA_VM);
    File stock = new File(gameDir, JAVA_VM_STOCK);
    if (!stock.isDirectory()) return;
    if (javaVm.exists()) deleteTree(javaVm);
    if (!stock.renameTo(javaVm)) log.warning("Couldn't restore the game's stock JVM");
    else log.info("Restored the game's stock JVM");
  }

  private static void deleteTree (File dir)
  {
    FileUtil.purgeDirectory(dir, new String[0]);
    dir.delete();
  }

  /** The agent and -D lines for extra.txt, in the launcher's stored percent units. */
  public static List<String> extraTxtLines (int scalePercent, int sharpenPercent)
  {
    return List.of(
      "-javaagent:" + AGENT_PATH,
      String.format(Locale.ROOT, "-Dskgfx.scale=%.2f", scalePercent / 100f),
      String.format(Locale.ROOT, "-Dskgfx.sharpen=%.2f", sharpenPercent / 100f));
  }

  /** Whether a jlink {@code release} file lists the {@code java.instrument} module. */
  public static boolean releaseHasInstrument (File releaseFile)
  {
    try {
      for (String line : Files.readAllLines(releaseFile.toPath())) {
        if (line.startsWith("MODULES=")) return line.contains("java.instrument");
      }
    } catch (IOException e) {
      log.warning("Couldn't read the game JVM's release file", "path", releaseFile, "error", e);
    }
    return false;
  }

  /** The game's required Java feature version, from getdown.txt's java_version (e.g. 25000000 -> 25). */
  int requiredFeatureVersion ()
  {
    try {
      for (String line : Files.readAllLines(new File(LauncherGlobals.USER_DIR, "getdown.txt").toPath())) {
        String trimmed = line.trim();
        if (trimmed.startsWith("java_version")) {
          String value = trimmed.substring(trimmed.indexOf('=') + 1).trim();
          return (int) (Long.parseLong(value) / 1_000_000L);
        }
      }
    } catch (IOException | NumberFormatException e) {
      log.warning("Couldn't read java_version from getdown.txt", "error", e);
    }
    return Runtime.version().feature();
  }

  private static void copyTree (Path source, Path target)
    throws IOException
  {
    Files.walkFileTree(source, new SimpleFileVisitor<Path>()
    {
      @Override
      public FileVisitResult preVisitDirectory (Path dir, BasicFileAttributes attrs)
        throws IOException
      {
        Files.createDirectories(target.resolve(source.relativize(dir)));
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile (Path file, BasicFileAttributes attrs)
        throws IOException
      {
        Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
        return FileVisitResult.CONTINUE;
      }
    });
  }
}
