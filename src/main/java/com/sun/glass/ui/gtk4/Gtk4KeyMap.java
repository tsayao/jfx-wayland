/*
 * Copyright (c) 2026, Thiago Sayão. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  The copyright holder designates
 * this particular file as subject to the "Classpath" exception as provided
 * in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package com.sun.glass.ui.gtk4;

import com.sun.glass.events.KeyEvent;
import com.sun.glass.ui.gtk4.wrappers.Gdk;
import com.sun.glass.ui.gtk4.wrappers.GdkDevice;
import com.sun.glass.ui.gtk4.wrappers.GdkDisplay;

import java.util.HashMap;
import java.util.Map;

final class Gtk4KeyMap {

    static final int GDK_SHIFT_MASK = 1;

    static final int GDK_CONTROL_MASK = 1 << 2;
    static final int GDK_ALT_MASK = 1 << 3;
    static final int GDK_SUPER_MASK = 1 << 26;
    static final int GDK_BUTTON1_MASK = 1 << 8;
    static final int GDK_BUTTON2_MASK = 1 << 9;
    static final int GDK_BUTTON3_MASK = 1 << 10;
    static final int GDK_BUTTON4_MASK = 1 << 11;
    static final int GDK_BUTTON5_MASK = 1 << 12;
    static final int GDK_META_MASK = 1 << 28;
    static final int GDK_KEY_Return = 0xff0d;

    static final int GDK_KEY_Escape = 0xff1b;
    static final int GDK_KEY_BackSpace = 0xff08;
    static final int GDK_KEY_Tab = 0xff09;
    static final int GDK_KEY_Clear = 0xff0b;
    static final int GDK_KEY_Delete = 0xffff;
    static final int GDK_KEY_Insert = 0xff63;
    static final int GDK_KEY_Home = 0xff50;
    static final int GDK_KEY_End = 0xff57;
    static final int GDK_KEY_Page_Up = 0xff55;
    static final int GDK_KEY_Page_Down = 0xff56;
    static final int GDK_KEY_Left = 0xff51;
    static final int GDK_KEY_Up = 0xff52;
    static final int GDK_KEY_Right = 0xff53;
    static final int GDK_KEY_Down = 0xff54;
    static final int GDK_KEY_Shift_L = 0xffe1;
    static final int GDK_KEY_Shift_R = 0xffe2;
    static final int GDK_KEY_Control_L = 0xffe3;
    static final int GDK_KEY_Control_R = 0xffe4;
    static final int GDK_KEY_Alt_L = 0xffe9;
    static final int GDK_KEY_Alt_R = 0xffea;
    static final int GDK_KEY_Super_L = 0xffeb;
    static final int GDK_KEY_Super_R = 0xffec;
    static final int GDK_KEY_Meta_L = 0xffe7;
    static final int GDK_KEY_Meta_R = 0xffe8;
    static final int GDK_KEY_Menu = 0xff67;
    static final int GDK_KEY_Caps_Lock = 0xffe5;
    static final int GDK_KEY_Num_Lock = 0xff7f;
    static final int GDK_KEY_Scroll_Lock = 0xff14;
    static final int GDK_KEY_space = 0x020;
    static final int GDK_KEY_Print = 0xff61;
    static final int GDK_KEY_Pause = 0xff13;
    static final int GDK_KEY_Help = 0xff6a;

    static final int GDK_KEY_F1 = 0xffbe;
    static final int GDK_KEY_F2 = 0xffbf;
    static final int GDK_KEY_F3 = 0xffc0;
    static final int GDK_KEY_F4 = 0xffc1;
    static final int GDK_KEY_F5 = 0xffc2;
    static final int GDK_KEY_F6 = 0xffc3;
    static final int GDK_KEY_F7 = 0xffc4;
    static final int GDK_KEY_F8 = 0xffc5;
    static final int GDK_KEY_F9 = 0xffc6;
    static final int GDK_KEY_F10 = 0xffc7;
    static final int GDK_KEY_F11 = 0xffc8;
    static final int GDK_KEY_F12 = 0xffc9;

    static final int GDK_KEY_KP_0 = 0xffb0;
    static final int GDK_KEY_KP_1 = 0xffb1;
    static final int GDK_KEY_KP_2 = 0xffb2;
    static final int GDK_KEY_KP_3 = 0xffb3;
    static final int GDK_KEY_KP_4 = 0xffb4;
    static final int GDK_KEY_KP_5 = 0xffb5;
    static final int GDK_KEY_KP_6 = 0xffb6;
    static final int GDK_KEY_KP_7 = 0xffb7;
    static final int GDK_KEY_KP_8 = 0xffb8;
    static final int GDK_KEY_KP_9 = 0xffb9;
    static final int GDK_KEY_KP_Enter = 0xff8d;
    static final int GDK_KEY_KP_Home = 0xff95;
    static final int GDK_KEY_KP_Left = 0xff96;
    static final int GDK_KEY_KP_Up = 0xff97;
    static final int GDK_KEY_KP_Right = 0xff98;
    static final int GDK_KEY_KP_Down = 0xff99;
    static final int GDK_KEY_KP_Page_Up = 0xff9a;
    static final int GDK_KEY_KP_Page_Down = 0xff9b;
    static final int GDK_KEY_KP_End = 0xff9c;
    static final int GDK_KEY_KP_Begin = 0xff9d;
    static final int GDK_KEY_KP_Insert = 0xff9e;
    static final int GDK_KEY_KP_Delete = 0xff9f;
    static final int GDK_KEY_KP_Multiply = 0xffaa;
    static final int GDK_KEY_KP_Add = 0xffab;
    static final int GDK_KEY_KP_Separator = 0xffac;
    static final int GDK_KEY_KP_Subtract = 0xffad;
    static final int GDK_KEY_KP_Decimal = 0xffae;
    static final int GDK_KEY_KP_Divide = 0xffaf;

    static final int GDK_KEY_exclamdown = 0x0a1;
    static final int GDK_KEY_EuroSign = 0x20ac;

    static final int GDK_KEY_ISO_Level3_Shift = 0xfe03;
    static final int GDK_KEY_dead_grave = 0xfe50;
    static final int GDK_KEY_dead_acute = 0xfe51;
    static final int GDK_KEY_dead_circumflex = 0xfe52;
    static final int GDK_KEY_dead_tilde = 0xfe53;
    static final int GDK_KEY_dead_macron = 0xfe54;
    static final int GDK_KEY_dead_breve = 0xfe55;
    static final int GDK_KEY_dead_abovedot = 0xfe56;
    static final int GDK_KEY_dead_diaeresis = 0xfe57;
    static final int GDK_KEY_dead_abovering = 0xfe58;
    static final int GDK_KEY_dead_doubleacute = 0xfe59;
    static final int GDK_KEY_dead_caron = 0xfe5a;
    static final int GDK_KEY_dead_cedilla = 0xfe5b;
    static final int GDK_KEY_dead_ogonek = 0xfe5c;
    static final int GDK_KEY_dead_iota = 0xfe5d;
    static final int GDK_KEY_dead_voiced_sound = 0xfe5e;
    static final int GDK_KEY_dead_semivoiced_sound = 0xfe5f;

    private Gtk4KeyMap() {
    }

    static int toGlassKey(int keyval) {
        if (keyval >= 'a' && keyval <= 'z') {
            return KeyEvent.VK_A + (keyval - 'a');
        }
        if (keyval >= 'A' && keyval <= 'Z') {
            return KeyEvent.VK_A + (keyval - 'A');
        }
        if (keyval >= '0' && keyval <= '9') {
            return KeyEvent.VK_0 + (keyval - '0');
        }

        return switch (keyval) {
            case GDK_KEY_Return -> KeyEvent.VK_ENTER;
            case GDK_KEY_Escape -> KeyEvent.VK_ESCAPE;
            case GDK_KEY_BackSpace -> KeyEvent.VK_BACKSPACE;
            case GDK_KEY_Tab -> KeyEvent.VK_TAB;
            case GDK_KEY_Clear -> KeyEvent.VK_CLEAR;
            case GDK_KEY_Delete -> KeyEvent.VK_DELETE;
            case GDK_KEY_Insert -> KeyEvent.VK_INSERT;
            case GDK_KEY_Home -> KeyEvent.VK_HOME;
            case GDK_KEY_End -> KeyEvent.VK_END;
            case GDK_KEY_Page_Up -> KeyEvent.VK_PAGE_UP;
            case GDK_KEY_Page_Down -> KeyEvent.VK_PAGE_DOWN;
            case GDK_KEY_Left -> KeyEvent.VK_LEFT;
            case GDK_KEY_Up -> KeyEvent.VK_UP;
            case GDK_KEY_Right -> KeyEvent.VK_RIGHT;
            case GDK_KEY_Down -> KeyEvent.VK_DOWN;
            case GDK_KEY_Shift_L, GDK_KEY_Shift_R -> KeyEvent.VK_SHIFT;
            case GDK_KEY_Control_L, GDK_KEY_Control_R -> KeyEvent.VK_CONTROL;
            case GDK_KEY_Alt_L -> KeyEvent.VK_ALT;
            case GDK_KEY_Alt_R -> KeyEvent.VK_ALT_GRAPH;
            case GDK_KEY_Super_L, GDK_KEY_Super_R -> KeyEvent.VK_WINDOWS;
            case GDK_KEY_Meta_L -> KeyEvent.VK_WINDOWS;
            case GDK_KEY_Meta_R -> KeyEvent.VK_CONTEXT_MENU;
            case GDK_KEY_Menu -> KeyEvent.VK_CONTEXT_MENU;
            case GDK_KEY_Caps_Lock -> KeyEvent.VK_CAPS_LOCK;
            case GDK_KEY_Num_Lock -> KeyEvent.VK_NUM_LOCK;
            case GDK_KEY_Scroll_Lock -> KeyEvent.VK_SCROLL_LOCK;
            case GDK_KEY_space -> KeyEvent.VK_SPACE;
            case GDK_KEY_Print -> KeyEvent.VK_PRINTSCREEN;
            case GDK_KEY_Pause -> KeyEvent.VK_PAUSE;
            case GDK_KEY_Help -> KeyEvent.VK_HELP;
            case GDK_KEY_F1 -> KeyEvent.VK_F1;
            case GDK_KEY_F2 -> KeyEvent.VK_F2;
            case GDK_KEY_F3 -> KeyEvent.VK_F3;
            case GDK_KEY_F4 -> KeyEvent.VK_F4;
            case GDK_KEY_F5 -> KeyEvent.VK_F5;
            case GDK_KEY_F6 -> KeyEvent.VK_F6;
            case GDK_KEY_F7 -> KeyEvent.VK_F7;
            case GDK_KEY_F8 -> KeyEvent.VK_F8;
            case GDK_KEY_F9 -> KeyEvent.VK_F9;
            case GDK_KEY_F10 -> KeyEvent.VK_F10;
            case GDK_KEY_F11 -> KeyEvent.VK_F11;
            case GDK_KEY_F12 -> KeyEvent.VK_F12;
            case GDK_KEY_KP_0 -> KeyEvent.VK_NUMPAD0;
            case GDK_KEY_KP_1 -> KeyEvent.VK_NUMPAD1;
            case GDK_KEY_KP_2 -> KeyEvent.VK_NUMPAD2;
            case GDK_KEY_KP_3 -> KeyEvent.VK_NUMPAD3;
            case GDK_KEY_KP_4 -> KeyEvent.VK_NUMPAD4;
            case GDK_KEY_KP_5 -> KeyEvent.VK_NUMPAD5;
            case GDK_KEY_KP_6 -> KeyEvent.VK_NUMPAD6;
            case GDK_KEY_KP_7 -> KeyEvent.VK_NUMPAD7;
            case GDK_KEY_KP_8 -> KeyEvent.VK_NUMPAD8;
            case GDK_KEY_KP_9 -> KeyEvent.VK_NUMPAD9;
            case GDK_KEY_KP_Enter -> KeyEvent.VK_ENTER;
            case GDK_KEY_KP_Home -> KeyEvent.VK_HOME;
            case GDK_KEY_KP_Left -> KeyEvent.VK_LEFT;
            case GDK_KEY_KP_Up -> KeyEvent.VK_UP;
            case GDK_KEY_KP_Right -> KeyEvent.VK_RIGHT;
            case GDK_KEY_KP_Down -> KeyEvent.VK_DOWN;
            case GDK_KEY_KP_Page_Up -> KeyEvent.VK_PAGE_UP;
            case GDK_KEY_KP_Page_Down -> KeyEvent.VK_PAGE_DOWN;
            case GDK_KEY_KP_End -> KeyEvent.VK_END;
            case GDK_KEY_KP_Begin -> KeyEvent.VK_CLEAR;
            case GDK_KEY_KP_Insert -> KeyEvent.VK_INSERT;
            case GDK_KEY_KP_Delete -> KeyEvent.VK_DELETE;
            case GDK_KEY_KP_Multiply -> KeyEvent.VK_MULTIPLY;
            case GDK_KEY_KP_Add -> KeyEvent.VK_ADD;
            case GDK_KEY_KP_Separator -> KeyEvent.VK_SEPARATOR;
            case GDK_KEY_KP_Subtract -> KeyEvent.VK_SUBTRACT;
            case GDK_KEY_KP_Decimal -> KeyEvent.VK_DECIMAL;
            case GDK_KEY_KP_Divide -> KeyEvent.VK_DIVIDE;
            case ',' -> KeyEvent.VK_COMMA;
            case '-' -> KeyEvent.VK_MINUS;
            case '.' -> KeyEvent.VK_PERIOD;
            case '/' -> KeyEvent.VK_SLASH;
            case ';' -> KeyEvent.VK_SEMICOLON;
            case '=' -> KeyEvent.VK_EQUALS;
            case '[' -> KeyEvent.VK_OPEN_BRACKET;
            case ']' -> KeyEvent.VK_CLOSE_BRACKET;
            case '\\' -> KeyEvent.VK_BACK_SLASH;
            case '|' -> KeyEvent.VK_BACK_SLASH;
            case '\'' -> KeyEvent.VK_QUOTE;
            case '`' -> KeyEvent.VK_BACK_QUOTE;
            case '&' -> KeyEvent.VK_AMPERSAND;
            case '*' -> KeyEvent.VK_ASTERISK;
            case '"' -> KeyEvent.VK_DOUBLE_QUOTE;
            case '<' -> KeyEvent.VK_LESS;
            case '>' -> KeyEvent.VK_GREATER;
            case '{' -> KeyEvent.VK_BRACELEFT;
            case '}' -> KeyEvent.VK_BRACERIGHT;
            case '@' -> KeyEvent.VK_AT;
            case ':' -> KeyEvent.VK_COLON;
            case '^' -> KeyEvent.VK_CIRCUMFLEX;
            case '$' -> KeyEvent.VK_DOLLAR;
            case '!' -> KeyEvent.VK_EXCLAMATION;
            case '(' -> KeyEvent.VK_LEFT_PARENTHESIS;
            case '#' -> KeyEvent.VK_NUMBER_SIGN;
            case '+' -> KeyEvent.VK_PLUS;
            case ')' -> KeyEvent.VK_RIGHT_PARENTHESIS;
            case '_' -> KeyEvent.VK_UNDERSCORE;
            case GDK_KEY_exclamdown -> KeyEvent.VK_INV_EXCLAMATION;
            case GDK_KEY_EuroSign -> KeyEvent.VK_EURO_SIGN;
            case GDK_KEY_ISO_Level3_Shift -> KeyEvent.VK_ALT_GRAPH;
            case GDK_KEY_dead_grave -> KeyEvent.VK_DEAD_GRAVE;
            case GDK_KEY_dead_acute -> KeyEvent.VK_DEAD_ACUTE;
            case GDK_KEY_dead_circumflex -> KeyEvent.VK_DEAD_CIRCUMFLEX;
            case GDK_KEY_dead_tilde -> KeyEvent.VK_DEAD_TILDE;
            case GDK_KEY_dead_macron -> KeyEvent.VK_DEAD_MACRON;
            case GDK_KEY_dead_breve -> KeyEvent.VK_DEAD_BREVE;
            case GDK_KEY_dead_abovedot -> KeyEvent.VK_DEAD_ABOVEDOT;
            case GDK_KEY_dead_diaeresis -> KeyEvent.VK_DEAD_DIAERESIS;
            case GDK_KEY_dead_abovering -> KeyEvent.VK_DEAD_ABOVERING;
            case GDK_KEY_dead_doubleacute -> KeyEvent.VK_DEAD_DOUBLEACUTE;
            case GDK_KEY_dead_caron -> KeyEvent.VK_DEAD_CARON;
            case GDK_KEY_dead_cedilla -> KeyEvent.VK_DEAD_CEDILLA;
            case GDK_KEY_dead_ogonek -> KeyEvent.VK_DEAD_OGONEK;
            case GDK_KEY_dead_iota -> KeyEvent.VK_DEAD_IOTA;
            case GDK_KEY_dead_voiced_sound -> KeyEvent.VK_DEAD_VOICED_SOUND;
            case GDK_KEY_dead_semivoiced_sound -> KeyEvent.VK_DEAD_SEMIVOICED_SOUND;
            default -> KeyEvent.VK_UNDEFINED;
        };
    }

    static int toGlassModifier(int gdkState) {
        int mod = 0;
        if ((gdkState & GDK_SHIFT_MASK) != 0) {
            mod |= KeyEvent.MODIFIER_SHIFT;
        }
        if ((gdkState & GDK_CONTROL_MASK) != 0) {
            mod |= KeyEvent.MODIFIER_CONTROL;
        }
        if ((gdkState & GDK_ALT_MASK) != 0) {
            mod |= KeyEvent.MODIFIER_ALT;
        }
        if ((gdkState & GDK_SUPER_MASK) != 0) {
            mod |= KeyEvent.MODIFIER_WINDOWS;
        }
        if ((gdkState & GDK_BUTTON1_MASK) != 0) {
            mod |= KeyEvent.MODIFIER_BUTTON_PRIMARY;
        }
        if ((gdkState & GDK_BUTTON2_MASK) != 0) {
            mod |= KeyEvent.MODIFIER_BUTTON_MIDDLE;
        }
        if ((gdkState & GDK_BUTTON3_MASK) != 0) {
            mod |= KeyEvent.MODIFIER_BUTTON_SECONDARY;
        }
        if ((gdkState & GDK_BUTTON4_MASK) != 0) {
            mod |= KeyEvent.MODIFIER_BUTTON_BACK;
        }
        if ((gdkState & GDK_BUTTON5_MASK) != 0) {
            mod |= KeyEvent.MODIFIER_BUTTON_FORWARD;
        }
        if ((gdkState & GDK_META_MASK) != 0) {
            mod |= KeyEvent.MODIFIER_ALT;
        }
        return mod;
    }

    static int glassKeyToModifier(int glassKey) {
        return switch (glassKey) {
            case KeyEvent.VK_SHIFT -> KeyEvent.MODIFIER_SHIFT;
            case KeyEvent.VK_ALT, KeyEvent.VK_ALT_GRAPH -> KeyEvent.MODIFIER_ALT;
            case KeyEvent.VK_CONTROL -> KeyEvent.MODIFIER_CONTROL;
            case KeyEvent.VK_WINDOWS -> KeyEvent.MODIFIER_WINDOWS;
            default -> 0;
        };
    }

    /**
     * Returns the character generated by a key event, or 0 if none. Control
     * plus a letter generates the corresponding control character.
     */
    static char toChar(int keyval, int state) {
        int unicode = Gdk.keyvalToUnicode(keyval);
        if (unicode <= 0 || unicode > 0xFFFF) {
            return 0;
        }
        if (unicode >= 'a' && unicode <= 'z' && (state & GDK_CONTROL_MASK) != 0) {
            unicode = unicode - 'a' + 1;
        }
        return (char) unicode;
    }

    // Characters seen on the keyboard, mapped to the key code of the key that
    // generates them. Used to answer getKeyCodeForChar for the current layout.
    private static final Map<Integer, Integer> charToKeyCode = new HashMap<>();

    /**
     * Assigns a Glass key code to a key event. The key code identifies the
     * physical key: it is derived from the unshifted keyval on the current
     * layout, or on the first layout when the current one (e.g. Cyrillic)
     * produces keyvals that have no key code.
     */
    static int getGlassKey(int keyval, int keycode, int state, int layout) {
        if (isKeypad(keyval)) {
            // The keypad keyval already reflects the Num Lock state
            return toGlassKey(keyval);
        }

        GdkDisplay display = GdkDisplay.getDefault();
        int base = translate(display, keycode, 0, layout);
        int key = base != 0 ? toGlassKey(base) : KeyEvent.VK_UNDEFINED;

        if (key == KeyEvent.VK_UNDEFINED && layout != 0) {
            int latin = translate(display, keycode, 0, 0);
            if (latin != 0) {
                key = toGlassKey(latin);
            }
        }

        if (key == KeyEvent.VK_UNDEFINED) {
            key = toGlassKey(keyval);
        }

        if (key != KeyEvent.VK_UNDEFINED) {
            recordCharacter(display, keycode, 0, layout, key);
            recordCharacter(display, keycode, GDK_SHIFT_MASK, layout, key);
        }
        return key;
    }

    private static int translate(GdkDisplay display, int keycode, int state, int layout) {
        int[] keyval = new int[1];
        if (display.translateKey(keycode, state, layout, keyval, null, null, null)) {
            return keyval[0];
        }
        return 0;
    }

    private static void recordCharacter(GdkDisplay display, int keycode, int state, int layout, int key) {
        int keyval = translate(display, keycode, state, layout);
        int unicode = keyval != 0 ? Gdk.keyvalToUnicode(keyval) : 0;
        if (unicode > 0) {
            charToKeyCode.put(unicode, key);
        }
    }

    private static boolean isKeypad(int keyval) {
        return keyval >= 0xff80 && keyval <= 0xffbd;
    }

    /**
     * The reverse of {@link #getGlassKey} for a character, for the Robot and
     * key combinations.
     */
    static int getKeyCodeForChar(char c, int hint) {
        Integer known = charToKeyCode.get((int) c);
        if (known != null) {
            return known;
        }
        // Not seen yet: fall back to the keyval, ignoring the layout. This handles
        // space and A-Z on Latin layouts.
        int keyval = Gdk.unicodeToKeyval(c);
        if (keyval == (c | 0x01000000)) {
            return KeyEvent.VK_UNDEFINED;
        }
        return toGlassKey(Gdk.keyvalToLower(keyval));
    }

    /**
     * The evdev keycode of the key that the robot presses for a Glass key:
     * the key of the active layout whose keyval, without modifiers, has that
     * key code (as {@link #getGlassKey} assigns them to the key events), or
     * one that has it with Shift; on the first layout when the active one
     * (e.g. Cyrillic) has none. The keys of the keypad, whose keyvals depend
     * on Num Lock, and the keys that no layout has, come from a table of the
     * evdev keycodes. Called on the event thread.
     */
    static int toRobotKeycode(int glassKey) {
        if (!isNumpadKey(glassKey)) {
            GdkDisplay display = GdkDisplay.getDefault();
            int layout = activeLayout(display);
            int keycode = findKeycode(display, glassKey, layout);
            if (keycode < 0 && layout != 0) {
                keycode = findKeycode(display, glassKey, 0);
            }
            if (keycode >= XKB_KEYCODE_OFFSET) {
                return keycode - XKB_KEYCODE_OFFSET;
            }
        }
        return toEvdevKeycode(glassKey);
    }

    // The keycodes of GDK on Wayland are those of XKB: evdev plus 8
    private static final int XKB_KEYCODE_OFFSET = 8;
    private static final int MAX_XKB_KEYCODE = 255;

    private static int findKeycode(GdkDisplay display, int glassKey, int layout) {
        for (int state : new int[] {0, GDK_SHIFT_MASK}) {
            for (int keycode = XKB_KEYCODE_OFFSET; keycode <= MAX_XKB_KEYCODE; keycode++) {
                int keyval = translate(display, keycode, state, layout);
                // The keypad also has Enter, Home, the arrows...: the main keys come first
                if (keyval != 0 && !isKeypad(keyval) && toGlassKey(keyval) == glassKey) {
                    return keycode;
                }
            }
        }
        return -1;
    }

    /** The layout of the keyboard in use, or the first one when GTK (before 4.18) does not tell. */
    private static int activeLayout(GdkDisplay display) {
        try {
            GdkDevice keyboard = display.getDefaultSeat().getKeyboard();
            return keyboard == null || keyboard.isNull() ? 0 : Math.max(keyboard.getActiveLayoutIndex(), 0);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static boolean isNumpadKey(int glassKey) {
        return (glassKey >= KeyEvent.VK_NUMPAD0 && glassKey <= KeyEvent.VK_NUMPAD9)
                || glassKey == KeyEvent.VK_MULTIPLY || glassKey == KeyEvent.VK_ADD
                || glassKey == KeyEvent.VK_SUBTRACT || glassKey == KeyEvent.VK_DIVIDE
                || glassKey == KeyEvent.VK_DECIMAL || glassKey == KeyEvent.VK_SEPARATOR;
    }

    /**
     * The evdev keycodes of the keys of a US layout, for the keys that the
     * active layout does not map.
     */
    static int toEvdevKeycode(int glassKey) {
        if (glassKey >= KeyEvent.VK_A && glassKey <= KeyEvent.VK_Z) {
            int[] qwerty = {
                30, 48, 46, 32, 18, 33, 34, 35, 23, 36,
                37, 38, 50, 49, 24, 25, 16, 19, 31, 20,
                22, 47, 17, 45, 21, 44
            };
            return qwerty[glassKey - KeyEvent.VK_A];
        }
        if (glassKey >= KeyEvent.VK_0 && glassKey <= KeyEvent.VK_9) {
            return glassKey == KeyEvent.VK_0 ? 11 : (glassKey - KeyEvent.VK_0 + 1);
        }
        if (glassKey >= KeyEvent.VK_F1 && glassKey <= KeyEvent.VK_F10) {
            return 59 + (glassKey - KeyEvent.VK_F1);
        }
        if (glassKey == KeyEvent.VK_F11) return 87;
        if (glassKey == KeyEvent.VK_F12) return 88;
        return switch (glassKey) {
            case KeyEvent.VK_ESCAPE -> 1;
            case KeyEvent.VK_BACKSPACE -> 14;
            case KeyEvent.VK_TAB -> 15;
            case KeyEvent.VK_ENTER -> 28;
            case KeyEvent.VK_SHIFT -> 42;
            case KeyEvent.VK_CONTROL -> 29;
            case KeyEvent.VK_ALT -> 56;
            case KeyEvent.VK_PAUSE -> 119;
            case KeyEvent.VK_CAPS_LOCK -> 58;
            case KeyEvent.VK_SPACE -> 57;
            case KeyEvent.VK_PAGE_UP -> 104;
            case KeyEvent.VK_PAGE_DOWN -> 109;
            case KeyEvent.VK_END -> 107;
            case KeyEvent.VK_HOME -> 102;
            case KeyEvent.VK_LEFT -> 105;
            case KeyEvent.VK_UP -> 103;
            case KeyEvent.VK_RIGHT -> 106;
            case KeyEvent.VK_DOWN -> 108;
            case KeyEvent.VK_INSERT -> 110;
            case KeyEvent.VK_DELETE -> 111;
            case KeyEvent.VK_WINDOWS -> 125;
            case KeyEvent.VK_NUM_LOCK -> 69;
            case KeyEvent.VK_SCROLL_LOCK -> 70;
            case KeyEvent.VK_PRINTSCREEN -> 99;
            case KeyEvent.VK_MINUS -> 12;
            case KeyEvent.VK_EQUALS -> 13;
            case KeyEvent.VK_OPEN_BRACKET -> 26;
            case KeyEvent.VK_CLOSE_BRACKET -> 27;
            case KeyEvent.VK_BACK_SLASH -> 43;
            case KeyEvent.VK_SEMICOLON -> 39;
            case KeyEvent.VK_QUOTE -> 40;
            case KeyEvent.VK_BACK_QUOTE -> 41;
            case KeyEvent.VK_COMMA -> 51;
            case KeyEvent.VK_PERIOD -> 52;
            case KeyEvent.VK_SLASH -> 53;
            case KeyEvent.VK_NUMPAD0 -> 82;
            case KeyEvent.VK_NUMPAD1 -> 79;
            case KeyEvent.VK_NUMPAD2 -> 80;
            case KeyEvent.VK_NUMPAD3 -> 81;
            case KeyEvent.VK_NUMPAD4 -> 75;
            case KeyEvent.VK_NUMPAD5 -> 76;
            case KeyEvent.VK_NUMPAD6 -> 77;
            case KeyEvent.VK_NUMPAD7 -> 71;
            case KeyEvent.VK_NUMPAD8 -> 72;
            case KeyEvent.VK_NUMPAD9 -> 73;
            case KeyEvent.VK_MULTIPLY -> 55;
            case KeyEvent.VK_ADD -> 78;
            case KeyEvent.VK_SUBTRACT -> 74;
            case KeyEvent.VK_DIVIDE -> 98;
            case KeyEvent.VK_DECIMAL, KeyEvent.VK_SEPARATOR -> 83;
            case KeyEvent.VK_ALT_GRAPH -> 100;
            case KeyEvent.VK_CONTEXT_MENU -> 127;
            default -> -1;
        };
    }
}

