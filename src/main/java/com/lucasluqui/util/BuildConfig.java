package com.lucasluqui.util;

import com.samskivert.util.Config;

public class BuildConfig
{
  protected static Config _build = new Config("build");

  public BuildConfig ()
  {
    // empty.
  }

  public static String getName ()
  {
    return _build.getValue("name", "Launcher");
  }

  public static String getVersion ()
  {
    return _build.getValue("version", "0");
  }

  public static String getSpiralviewVersion ()
  {
    return _build.getValue("spiralview_version", "0");
  }

  public static String getTortodroneVersion ()
  {
    return _build.getValue("tortodrone_version", "0");
  }

  /** Where the pinned Tortodrone release is downloaded from. */
  public static String getTortodroneUrl ()
  {
    return _build.getValue("tortodrone_url", "");
  }

  /** The SHA-256 of the engine jar bundled with the launcher (the one inside the pinned release). */
  public static String getTortodroneJarSha256 ()
  {
    return _build.getValue("tortodrone_jar_sha256", "");
  }

  /** The SHA-256 the downloaded Tortodrone release must have. */
  public static String getTortodroneSha256 ()
  {
    return _build.getValue("tortodrone_sha256", "");
  }

  public static boolean isSnapshot ()
  {
    return BuildConfig.getVersion().contains("SNAPSHOT");
  }
}