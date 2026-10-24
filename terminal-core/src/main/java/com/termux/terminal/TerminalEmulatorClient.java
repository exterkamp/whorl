package com.termux.terminal;

/** What {@link TerminalEmulator} itself needs from whoever hosts it. */
public interface TerminalEmulatorClient {

    void onTerminalCursorStateChange(boolean state);

    Integer getTerminalCursorStyle();

    void logError(String tag, String message);

    void logWarn(String tag, String message);
}
