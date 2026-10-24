package com.termux.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Where a {@link TerminalSession}'s bytes come from and go to: a local pty process
 * ({@link ProcessTransport}) or a remote channel such as SSH.
 * <p>
 * {@link #connect(int, int)} and {@link #waitFor()} are called on background threads and may block.
 */
public interface TerminalTransport {

    /** Establish the connection with an initial window size. Throwing ends the session with the message shown. */
    void connect(int columns, int rows) throws Exception;

    InputStream getInputStream() throws IOException;

    OutputStream getOutputStream() throws IOException;

    /** Inform the remote end of a new window size. */
    void resize(int columns, int rows);

    /**
     * Block until the remote end has gone away.
     *
     * @return if >= 0, an exit status; if < 0, a negated signal number.
     */
    int waitFor();

    /** Tear the connection down. Must be safe to call more than once and from any thread. */
    void close();
}
