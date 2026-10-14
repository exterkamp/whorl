package com.termux.terminal;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;

/** A local subprocess attached to a pseudo-terminal: what {@link TerminalSession} always used to be. */
public final class ProcessTransport implements TerminalTransport {

    private final String mExecutable;
    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;

    private int mFd = -1;
    private int mPid;
    private FileDescriptor mWrappedFd;

    public ProcessTransport(String executable, String cwd, String[] args, String[] env) {
        mExecutable = executable;
        mCwd = cwd;
        mArgs = args;
        mEnv = env;
    }

    @Override
    public void connect(int columns, int rows) throws Exception {
        int[] processId = new int[1];
        mFd = JNI.createSubprocess(mExecutable, mCwd, mArgs, mEnv, processId, rows, columns);
        mPid = processId[0];
        mWrappedFd = wrapFileDescriptor(mFd);
    }

    @Override
    public InputStream getInputStream() {
        return new FileInputStream(mWrappedFd);
    }

    @Override
    public OutputStream getOutputStream() {
        return new FileOutputStream(mWrappedFd);
    }

    @Override
    public void resize(int columns, int rows) {
        if (mFd >= 0) JNI.setPtyWindowSize(mFd, rows, columns);
    }

    @Override
    public int waitFor() {
        int status = JNI.waitFor(mPid);
        synchronized (this) {
            mPid = -1;
            if (mFd >= 0) {
                JNI.close(mFd);
                mFd = -1;
            }
        }
        return status;
    }

    /**
     * Ask the process to exit with SIGTERM, then SIGKILL it if it hasn't after a few seconds. The grace period
     * matters for mosh-client, which uses it to tell the server to shut down rather than linger forever.
     */
    @Override
    public synchronized void close() {
        final int pid = mPid;
        if (pid <= 0) return;
        signal(pid, OsConstants.SIGTERM);
        new Thread("ProcessTransportKill") {
            @Override
            public void run() {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    // Kill now.
                }
                synchronized (ProcessTransport.this) {
                    if (mPid == pid) signal(pid, OsConstants.SIGKILL);
                }
            }
        }.start();
    }

    private static void signal(int pid, int signal) {
        try {
            Os.kill(pid, signal);
        } catch (ErrnoException e) {
            // Already gone.
        }
    }

    private static FileDescriptor wrapFileDescriptor(int fileDescriptor) throws ReflectiveOperationException {
        FileDescriptor result = new FileDescriptor();
        Field descriptorField;
        try {
            descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
        } catch (NoSuchFieldException e) {
            // For desktop java:
            descriptorField = FileDescriptor.class.getDeclaredField("fd");
        }
        descriptorField.setAccessible(true);
        descriptorField.set(result, fileDescriptor);
        return result;
    }
}
