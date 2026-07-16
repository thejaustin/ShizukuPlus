package rikka.shizuku.server;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import java.io.IOException;
import moe.shizuku.server.IRemoteProcess;

public class ProxyRemoteProcess extends IRemoteProcess.Stub {
    private static final String TAG = "ProxyRemoteProcess";
    private final ParcelFileDescriptor pfd;
    private final int exitValue;

    public ProxyRemoteProcess(ParcelFileDescriptor pfd, int exitValue) {
        this.pfd = pfd;
        this.exitValue = exitValue;
    }

    @Override
    public ParcelFileDescriptor getOutputStream() {
        return null;
    }

    @Override
    public ParcelFileDescriptor getInputStream() {
        return pfd;
    }

    @Override
    public ParcelFileDescriptor getErrorStream() {
        return null;
    }

    @Override
    public int waitFor() {
        return exitValue;
    }

    @Override
    public int exitValue() {
        return exitValue;
    }

    @Override
    public void destroy() {
        try {
            if (pfd != null) pfd.close();
        } catch (IOException e) {
            Log.w(TAG, "Failed to close ParcelFileDescriptor in destroy()", e);
        }
    }

    @Override
    public boolean alive() {
        return false;
    }

    @Override
    public boolean waitForTimeout(long timeout, String unitName) {
        return true;
    }
}
