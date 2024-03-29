package com.sharing.file.data.ftp.transfer.free.ftpshare;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.sharing.file.data.ftp.transfer.free.ftpshare.server.SessionThread;
import com.sharing.file.data.ftp.transfer.free.ftpshare.server.TcpListener;

public class FtpServerService extends Service implements Runnable {
    private static final String TAG = FtpServerService.class.getSimpleName();

    // Service will (global) broadcast when server start/stop
    static public final String ACTION_STARTED = "be.ppareit.swiftp.FTPSERVER_STARTED";
    static public final String ACTION_STOPPED = "be.ppareit.swiftp.FTPSERVER_STOPPED";
    static public final String ACTION_FAILEDTOSTART = "be.ppareit.swiftp.FTPSERVER_FAILEDTOSTART";

    // RequestStartStopReceiver listens for these actions to start/stop this server
	static public final String ACTION_START_FTPSERVER = "be.ppareit.swiftp.ACTION_START_FTPSERVER";
    static public final String ACTION_STOP_FTPSERVER = "be.ppareit.swiftp.ACTION_STOP_FTPSERVER";

    protected static Thread serverThread = null;
    protected boolean shouldExit = false;

    public static final int BACKLOG = 21;
    public static final int MAX_SESSIONS = 5;
    public static final String WAKE_LOCK_TAG = "SwiFTP";

    // protected ServerSocketChannel wifiSocket;
    protected ServerSocket listenSocket;
    protected static WifiLock wifiLock = null;

    protected static List<String> sessionMonitor = new ArrayList<String>();
    protected static List<String> serverLog = new ArrayList<String>();
    protected static int uiLogLevel = Defaults.getUiLogLevel();

    // The server thread will check this often to look for incoming
    // connections. We are forced to use non-blocking accept() and polling
    // because we cannot wait forever in accept() if we want to be able
    // to receive an exit signal and cleanly exit.
    public static final int WAKE_INTERVAL_MS = 1000; // milliseconds

    private TcpListener wifiListener = null;
    private final List<SessionThread> sessionThreads = new ArrayList<SessionThread>();

    PowerManager.WakeLock wakeLock;

    public FtpServerService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        // We don't implement this functionality, so ignore it
        return null;
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "SwiFTP server created");
        return;
    }

    @SuppressWarnings("deprecation")
	@Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);

        shouldExit = false;
        int attempts = 10;
        // The previous server thread may still be cleaning up, wait for it to finish.
        while (serverThread != null) {
            Log.w(TAG, "Won't start, server thread exists");
            if (attempts > 0) {
                attempts--;
                Util.sleepIgnoreInterupt(1000);
            } else {
                Log.w(TAG, "Server thread already exists");
                return;
            }
        }
        Log.d(TAG, "Creating server thread");
        serverThread = new Thread(this);
        serverThread.start();
    }

    public static boolean isRunning() {
        // return true if and only if a server Thread is running
        if (serverThread == null) {
            Log.d(TAG, "Server is not running (null serverThread)");
            return false;
        }
        if (!serverThread.isAlive()) {
            Log.d(TAG, "serverThread non-null but !isAlive()");
        } else {
            Log.d(TAG, "Server is alive");
        }
        return true;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy() Stopping server");
        shouldExit = true;
        if (serverThread == null) {
            Log.w(TAG, "Stopping with null serverThread");
            return;
        } else {
            serverThread.interrupt();
            try {
                serverThread.join(10000); // wait 10 sec for server thread to finish
            } catch (InterruptedException e) {
            }
            if (serverThread.isAlive()) {
                Log.w(TAG, "Server thread failed to exit");
                // it may still exit eventually if we just leave the shouldExit flag set
            } else {
                Log.d(TAG, "serverThread join()ed ok");
                serverThread = null;
            }
        }
        try {
            if (listenSocket != null) {
                Log.i(TAG, "Closing listenSocket");
                listenSocket.close();
            }
        } catch (IOException e) {
        }

        if (wifiLock != null) {
            wifiLock.release();
            wifiLock = null;
        }
        Log.d(TAG, "FTPServerService.onDestroy() finished");
    }

    // This opens a listening socket on all interfaces.
    void setupListener() throws IOException {
        listenSocket = new ServerSocket();
        listenSocket.setReuseAddress(true);
        listenSocket.bind(new InetSocketAddress(Settings.getPortNumber()));
    }

    public void run() {
        Log.d(TAG, "Server thread running");

        // fail when there is no local network
        if (isConnectedToLocalNetwork() == false) {
            cleanupAndStopService();
            sendBroadcast(new Intent(ACTION_FAILEDTOSTART));
            return;
        }

        // Initialization of wifi, set up the socket
        try {
            setupListener();
        } catch (IOException e) {
            Log.w(TAG, "Error opening port, check your network connection.");
            // serverAddress = null;
            cleanupAndStopService();
            sendBroadcast(new Intent(ACTION_FAILEDTOSTART));
            return;
        }

        // @TODO: when using ethernet, is it needed to take wifi lock?
        takeWifiLock();
        takeWakeLock();

        // A socket is open now, so the FTP server is started, notify rest of world
        Log.i(TAG, "Ftp Server up and running, broadcasting ACTION_STARTED");
        sendBroadcast(new Intent(ACTION_STARTED));

        while (!shouldExit) {
            if (wifiListener != null) {
                if (!wifiListener.isAlive()) {
                    Log.d(TAG, "Joining crashed wifiListener thread");
                    try {
                        wifiListener.join();
                    } catch (InterruptedException e) {
                    }
                    wifiListener = null;
                }
            }
            if (wifiListener == null) {
                // Either our wifi listener hasn't been created yet, or has crashed,
                // so spawn it
                wifiListener = new TcpListener(listenSocket, this);
                wifiListener.start();
            }
            try {
                // TODO: think about using ServerSocket, and just closing
                // the main socket to send an exit signal
                Thread.sleep(WAKE_INTERVAL_MS);
            } catch (InterruptedException e) {
                Log.d(TAG, "Thread interrupted");
            }
        }

        terminateAllSessions();

        if (wifiListener != null) {
            wifiListener.quit();
            wifiListener = null;
        }
        shouldExit = false; // we handled the exit flag, so reset it to acknowledge
        Log.d(TAG, "Exiting cleanly, returning from run()");

        cleanupAndStopService();
    }

    private void terminateAllSessions() {
        Log.i(TAG, "Terminating " + sessionThreads.size() + " session thread(s)");
        synchronized (this) {
            for (SessionThread sessionThread : sessionThreads) {
                if (sessionThread != null) {
                    sessionThread.closeDataSocket();
                    sessionThread.closeSocket();
                }
            }
        }
    }

    public void cleanupAndStopService() {
        // Call the Android Service shutdown function
        stopSelf();
        releaseWifiLock();
        releaseWakeLock();
        sendBroadcast(new Intent(ACTION_STOPPED));
    }

    @SuppressWarnings("deprecation")
	private void takeWakeLock() {
        if (wakeLock == null) {
            Log.d(TAG, "About to take wake lock");
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            // Many devices seem to not properly honor a PARTIAL_WAKE_LOCK, which
            // should prevent CPU throttling. For these devices, we have a option
            // to force the phone into a full wake lock.
            if (Settings.shouldTakeFullWakeLock()) {
                Log.d(TAG, "Need to take full wake lock");
                wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, WAKE_LOCK_TAG);
            } else {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
            }
            wakeLock.setReferenceCounted(false);
        }
        Log.d(TAG, "Acquiring wake lock");
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        Log.d(TAG, "Releasing wake lock");
        if (wakeLock != null) {
            wakeLock.release();
            wakeLock = null;
            Log.d(TAG, "Finished releasing wake lock");
        } else {
            Log.e(TAG, "Couldn't release null wake lock");
        }
    }

    private void takeWifiLock() {
        Log.d(TAG, "Taking wifi lock");
        if (wifiLock == null) {
            WifiManager manager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
            wifiLock = manager.createWifiLock("SwiFTP");
            wifiLock.setReferenceCounted(false);
        }
        wifiLock.acquire();
    }

    private void releaseWifiLock() {
        Log.d(TAG, "Releasing wifi lock");
        if (wifiLock != null) {
            wifiLock.release();
            wifiLock = null;
        }
    }

    public void errorShutdown() {
        Log.e(TAG, "Service errorShutdown() called");
        cleanupAndStopService();
    }

    /**
     * Gets the local ip address
     * 
     * @return local ip adress or null if not found
     */
    public static InetAddress getLocalInetAddress() {
        if (isConnectedToLocalNetwork() == false) {
            Log.e(TAG, "getLocalInetAddress called and no connection");
            return null;
        }
        // TODO: next if block could probably be removed
        if (isConnectedUsingWifi() == true) {
            Context context = FtpServerApp.getAppContext();
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            int ipAddress = wm.getConnectionInfo().getIpAddress();
            if (ipAddress == 0)
                return null;
            return Util.intToInet(ipAddress);
        }
        // This next part should be able to get the local ip address, but in some case
        // I'm receiving the routable address
        try {
            Enumeration<NetworkInterface> netinterfaces = NetworkInterface
                    .getNetworkInterfaces();
            while (netinterfaces.hasMoreElements()) {
                NetworkInterface netinterface = netinterfaces.nextElement();
                Enumeration<InetAddress> adresses = netinterface.getInetAddresses();
                while (adresses.hasMoreElements()) {
                    InetAddress address = adresses.nextElement();
                    // this is the condition that sometimes gives problems
                    if (address.isLoopbackAddress() == false
                            && address.isLinkLocalAddress() == false)
                        return address;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Checks to see if we are connected to a local network, for instance wifi or ethernet
     * 
     * @return true if connected to a local network
     */
    public static boolean isConnectedToLocalNetwork() {
        Context context = FtpServerApp.getAppContext();
        ConnectivityManager cm = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        // @TODO: this is only defined starting in api level 13
        final int TYPE_ETHERNET = 0x00000009;
        return ni != null && ni.isConnected() == true
                && (ni.getType() & (ConnectivityManager.TYPE_WIFI | TYPE_ETHERNET)) != 0;
    }

    /**
     * Checks to see if we are connected using wifi
     * 
     * @return true if connected using wifi
     */
    public static boolean isConnectedUsingWifi() {
        Context context = FtpServerApp.getAppContext();
        ConnectivityManager cm = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected() == true
                && ni.getType() == ConnectivityManager.TYPE_WIFI;
    }

    public static List<String> getSessionMonitorContents() {
        return new ArrayList<String>(sessionMonitor);
    }

    public static List<String> getServerLogContents() {
        return new ArrayList<String>(serverLog);
    }

    public static void log(int msgLevel, String s) {
        serverLog.add(s);
        int maxSize = Defaults.getServerLogScrollBack();
        while (serverLog.size() > maxSize) {
            serverLog.remove(0);
        }
        // updateClients();
    }

    public static void writeMonitor(boolean incoming, String s) {
    }

    // public static void writeMonitor(boolean incoming, String s) {
    // if(incoming) {
    // s = "> " + s;
    // } else {
    // s = "< " + s;
    // }
    // sessionMonitor.add(s.trim());
    // int maxSize = Defaults.getSessionMonitorScrollBack();
    // while(sessionMonitor.size() > maxSize) {
    // sessionMonitor.remove(0);
    // }
    // updateClients();
    // }

    /**
     * The FTPServerService must know about all running session threads so they can be
     * terminated on exit. Called when a new session is created.
     */
    public void registerSessionThread(SessionThread newSession) {
        // Before adding the new session thread, clean up any finished session
        // threads that are present in the list.

        // Since we're not allowed to modify the list while iterating over
        // it, we construct a list in toBeRemoved of threads to remove
        // later from the sessionThreads list.
        synchronized (this) {
            List<SessionThread> toBeRemoved = new ArrayList<SessionThread>();
            for (SessionThread sessionThread : sessionThreads) {
                if (!sessionThread.isAlive()) {
                    Log.d(TAG, "Cleaning up finished session...");
                    try {
                        sessionThread.join();
                        Log.d(TAG, "Thread joined");
                        toBeRemoved.add(sessionThread);
                        sessionThread.closeSocket(); // make sure socket closed
                    } catch (InterruptedException e) {
                        Log.d(TAG, "Interrupted while joining");
                        // We will try again in the next loop iteration
                    }
                }
            }
            for (SessionThread removeThread : toBeRemoved) {
                sessionThreads.remove(removeThread);
            }

            // Cleanup is complete. Now actually add the new thread to the list.
            sessionThreads.add(newSession);
        }
        Log.d(TAG, "Registered session thread");
    }

}
