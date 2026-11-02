package pl.ciphervoice.client;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

final class WindowsThemeSupport {
    private interface User32Extra extends StdCallLibrary {
        User32Extra INSTANCE = Native.load("user32", User32Extra.class);
        HWND FindWindowW(char[] lpClassName, char[] lpWindowName);
    }

    private interface DwmApiExtra extends StdCallLibrary {
        DwmApiExtra INSTANCE = Native.load("dwmapi", DwmApiExtra.class);
        int DwmSetWindowAttribute(HWND hwnd, int dwAttribute, IntByReference pvAttribute, int cbAttribute);
    }

    private WindowsThemeSupport() {
    }

    static void enableDarkTitleBar(String windowTitle) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        Thread.ofVirtual().name("dark-title-bar-init").start(() -> {
            try {
                char[] titleChars = (windowTitle + "\0").toCharArray();
                for (int attempt = 0; attempt < 20; attempt++) {
                    Thread.sleep(50);
                    HWND hwnd = User32Extra.INSTANCE.FindWindowW(null, titleChars);
                    if (hwnd != null) {
                        IntByReference darkMode = new IntByReference(1);
                        // 20 is DWMWA_USE_IMMERSIVE_DARK_MODE on Win11 / Win10 20H1+
                        DwmApiExtra.INSTANCE.DwmSetWindowAttribute(hwnd, 20, darkMode, 4);
                        // 19 was used on Win10 1809/1903
                        DwmApiExtra.INSTANCE.DwmSetWindowAttribute(hwnd, 19, darkMode, 4);
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
        });
    }
}
