package com.lucasluqui.launcher;

import java.awt.Color;

public class CustomColors
{
  // SKapsule's accent (#AB4A81) is the only color besides black and white; the rest are status colors.
  public static final Color ACCENT          = new Color(171, 74, 129);
  public static final Color ACCENT_LIGHT    = new Color(201, 110, 164);
  public static final Color ACCENT_DARK     = new Color(122, 52, 92);

  public static final Color LAUNCHER        = ACCENT;
  public static final Color LAUNCH          = ACCENT;
  public static final Color LAUNCH_ALT      = ACCENT_DARK;
  public static final Color UPDATE          = new Color(34, 197, 94);
  public static final Color PREMIUM         = new Color(222, 150, 47);
  public static final Color DISCORD         = new Color(114, 137, 218);
  public static final Color BUY_ME_A_COFFEE = new Color(255, 221, 0);
  public static final Color KOFI            = BUY_ME_A_COFFEE;
  public static final Color CHANGELOG       = ACCENT_LIGHT;
  public static final Color SILVERWEAVE     = ACCENT;

  public static final Color LIGHT_RED     = new Color(247, 59, 59);
  public static final Color LIGHT_GREEN   = new Color(52, 219, 103);
  public static final Color RED           = new Color(194, 0, 0);
  public static final Color GREEN         = new Color(0, 194, 65);
  public static final Color DANGER        = new Color(251, 91, 91);
  public static final Color WARNING       = new Color(251, 198, 91);

  // Neutral black palette, the same surfaces as the SKapsule app (#000000, #0A0A0A, #141414, #1E1E1E, #333333)
  public static final Color INTERFACE_DEFAULT                   = new Color(170, 170, 170);
  public static final Color INTERFACE_DEFAULT_FOCUS             = new Color(51, 51, 51);
  public static final Color INTERFACE_DEFAULT_BACKGROUND        = new Color(30, 30, 30);
  public static final Color INTERFACE_DEFAULT_WINDOW_BORDER     = new Color(0, 0, 0, 160);

  public static final Color INTERFACE_COMPONENT_BACKGROUND            = new Color(32, 32, 32);
  public static final Color INTERFACE_COMPONENT_SELECTED_BACKGROUND   = new Color(42, 42, 42);
  public static final Color INTERFACE_COMPONENT_FOCUS                 = new Color(60, 60, 60);

  public static final Color INTERFACE_BUTTON_BACKGROUND         = new Color(30, 30, 30);
  public static final Color INTERFACE_TEXTFIELD_BACKGROUND      = new Color(24, 24, 24);

  public static final Color INTERFACE_TITLEBAR                  = new Color(0, 0, 0);

  public static final Color INTERFACE_SERVERSWITCHER_HOVER_BORDER           = ACCENT;
  public static final Color INTERFACE_SERVERSWITCHER_HOVER_BORDER_LOCKED    = new Color(251, 91, 91);

  public static final Color INTERFACE_SIDEPANE_BACKGROUND       = new Color(10, 10, 10);
  public static final Color INTERFACE_SIDEPANE_SUBBACKGROUND    = new Color(5, 5, 5);
  public static final Color INTERFACE_SIDEPANE_FOOTNOTE         = new Color(85, 85, 85);

  public static final Color INTERFACE_MAINPANE_BACKGROUND                 = new Color(20, 20, 20);
  public static final Color INTERFACE_MAINPANE_BACKGROUND_FOCUS           = new Color(30, 30, 30);
  public static final Color INTERFACE_MAINPANE_SUBBACKGROUND              = new Color(10, 10, 10);
  public static final Color INTERFACE_MAINPANE_SUBBACKGROUND_FOCUS_DARK   = new Color(5, 5, 5);
  public static final Color INTERFACE_MAINPANE_SUBBACKGROUND_FOCUS_LIGHT  = new Color(150, 150, 150);
  public static final Color INTERFACE_MAINPANE_SUBBACKGROUND_FOCUS_BRIGHT = new Color(175, 175, 175);
  public static final Color INTERFACE_MAINPANE_TRANSPARENT_BUTTON         = new Color(INTERFACE_MAINPANE_BACKGROUND.getRed(), INTERFACE_MAINPANE_BACKGROUND.getGreen(), INTERFACE_MAINPANE_BACKGROUND.getBlue(), 150);
  public static final Color INTERFACE_MAINPANE_FOOTNOTE                   = new Color(90, 90, 90);

  public static final Color INTERFACE_SETTINGS_BADGE_JVM_BACKGROUND       = new Color(217, 119, 7);
  public static final Color INTERFACE_SETTINGS_BADGE_JVM_FOREGROUND       = new Color(248, 159, 58);
  public static final Color INTERFACE_SETTINGS_BADGE_CODE_BACKGROUND      = new Color(140, 140, 140);
  public static final Color INTERFACE_SETTINGS_BADGE_CODE_FOREGROUND      = new Color(179, 179, 180);

  public static final Color INTERFACE_MODLIST_BADGE_BACKGROUND            = new Color(30, 30, 30);
  public static final Color INTERFACE_MODLIST_BADGE_RESOURCE_BACKGROUND   = new Color(0, 174, 255);
  public static final Color INTERFACE_MODLIST_BADGE_RESOURCE_FOREGROUND   = new Color(102, 207, 255);
  public static final Color INTERFACE_MODLIST_BADGE_CODE_BACKGROUND       = new Color(217, 119, 7);
  public static final Color INTERFACE_MODLIST_BADGE_CODE_FOREGROUND       = new Color(248, 159, 58);
  public static final Color INTERFACE_MODLIST_BADGE_PACK_BACKGROUND       = new Color(24, 217, 7);
  public static final Color INTERFACE_MODLIST_BADGE_PACK_FOREGROUND       = new Color(68, 248, 58);
  public static final Color INTERFACE_MODLIST_BADGE_CLASS_BACKGROUND      = new Color(191, 0, 255);
  public static final Color INTERFACE_MODLIST_BADGE_CLASS_FOREGROUND      = new Color(217, 102, 255);
}
