package com.sharing.file.data.ftp.transfer.free.ftpshare.server;

import java.io.File;
import java.lang.reflect.Constructor;

import com.sharing.file.data.ftp.transfer.free.ftpshare.Settings;

import android.util.Log;

public abstract class FtpCmd implements Runnable {
    private static final String TAG = FtpCmd.class.getSimpleName();

    protected SessionThread sessionThread;

    protected static CmdMap[] cmdClasses = { new CmdMap("SYST", CmdSYST.class),
            new CmdMap("USER", CmdUSER.class), new CmdMap("PASS", CmdPASS.class),
            new CmdMap("TYPE", CmdTYPE.class), new CmdMap("CWD", CmdCWD.class),
            new CmdMap("PWD", CmdPWD.class), new CmdMap("LIST", CmdLIST.class),
            new CmdMap("PASV", CmdPASV.class), new CmdMap("RETR", CmdRETR.class),
            new CmdMap("NLST", CmdNLST.class), new CmdMap("NOOP", CmdNOOP.class),
            new CmdMap("STOR", CmdSTOR.class), new CmdMap("DELE", CmdDELE.class),
            new CmdMap("RNFR", CmdRNFR.class), new CmdMap("RNTO", CmdRNTO.class),
            new CmdMap("RMD", CmdRMD.class), new CmdMap("MKD", CmdMKD.class),
            new CmdMap("OPTS", CmdOPTS.class), new CmdMap("PORT", CmdPORT.class),
            new CmdMap("QUIT", CmdQUIT.class), new CmdMap("FEAT", CmdFEAT.class),
            new CmdMap("SIZE", CmdSIZE.class), new CmdMap("CDUP", CmdCDUP.class),
            new CmdMap("APPE", CmdAPPE.class), new CmdMap("XCUP", CmdCDUP.class), // synonym
            new CmdMap("XPWD", CmdPWD.class), // synonym
            new CmdMap("XMKD", CmdMKD.class), // synonym
            new CmdMap("XRMD", CmdRMD.class) // synonym
    };

    public FtpCmd(SessionThread sessionThread) {
        this.sessionThread = sessionThread;
    }

    abstract public void run();

    protected static void dispatchCommand(SessionThread session, String inputString) {
        String[] strings = inputString.split(" ");
        String unrecognizedCmdMsg = "502 Command not recognized\r\n";
        if (strings == null) {
            // There was some egregious sort of parsing error
            String errString = "502 Command parse error\r\n";
            Log.d(TAG, errString);
            session.writeString(errString);
            return;
        }
        if (strings.length < 1) {
            Log.d(TAG, "No strings parsed");
            session.writeString(unrecognizedCmdMsg);
            return;
        }
        String verb = strings[0];
        if (verb.length() < 1) {
            Log.i(TAG, "Invalid command verb");
            session.writeString(unrecognizedCmdMsg);
            return;
        }
        FtpCmd cmdInstance = null;
        verb = verb.trim();
        verb = verb.toUpperCase();
        for (int i = 0; i < cmdClasses.length; i++) {

            if (cmdClasses[i].getName().equals(verb)) {
                // We found the correct command. We retrieve the corresponding
                // Class object, get the Constructor object for that Class, and
                // and use that Constructor to instantiate the correct FtpCmd
                // subclass. Yes, I'm serious.
                Constructor<? extends FtpCmd> constructor;
                try {
                    constructor = cmdClasses[i].getCommand().getConstructor(
                            new Class[] { SessionThread.class, String.class });
                } catch (NoSuchMethodException e) {
                    Log.e(TAG, "FtpCmd subclass lacks expected " + "constructor ");
                    return;
                }
                try {
                    cmdInstance = constructor.newInstance(new Object[] { session,
                            inputString });
                } catch (Exception e) {
                    Log.e(TAG, "Instance creation error on FtpCmd");
                    return;
                }
            }
        }
        if (cmdInstance == null) {
            // If we couldn't find a matching command,
            Log.d(TAG, "Ignoring unrecognized FTP verb: " + verb);
            session.writeString(unrecognizedCmdMsg);
            return;
        } else if (session.isAuthenticated()
                || cmdInstance.getClass().equals(CmdUSER.class)
                || cmdInstance.getClass().equals(CmdPASS.class)
                || cmdInstance.getClass().equals(CmdUSER.class)) {
            // Unauthenticated users can run only USER, PASS and QUIT
            cmdInstance.run();
        } else {
            session.writeString("530 Login first with USER and PASS\r\n");
        }
    }

    /**
     * An FTP parameter is that part of the input string that occurs after the first
     * space, including any subsequent spaces. Also, we want to chop off the trailing
     * '\r\n', if present.
     * 
     * Some parameters shouldn't be logged or output (e.g. passwords), so the caller can
     * use silent==true in that case.
     */
    static public String getParameter(String input, boolean silent) {
        if (input == null) {
            return "";
        }
        int firstSpacePosition = input.indexOf(' ');
        if (firstSpacePosition == -1) {
            return "";
        }
        String retString = input.substring(firstSpacePosition + 1);

        // Remove trailing whitespace
        // todo: trailing whitespace may be significant, just remove \r\n
        retString = retString.replaceAll("\\s+$", "");

        if (!silent) {
            Log.d(TAG, "Parsed argument: " + retString);
        }
        return retString;
    }

    /**
     * A wrapper around getParameter, for when we don't want it to be silent.
     */
    static public String getParameter(String input) {
        return getParameter(input, false);
    }

    public static File inputPathToChrootedFile(File existingPrefix, String param) {
        try {
            if (param.charAt(0) == '/') {
                // The STOR contained an absolute path
                File chroot = Settings.getChrootDir();
                return new File(chroot, param);
            }
        } catch (Exception e) {
        }

        // The STOR contained a relative path
        return new File(existingPrefix, param);
    }

    public boolean violatesChroot(File file) {
        try {
            // taking the canonical path as new devices have sdcard symlinked
            // for multiuser support
            File chroot = Settings.getChrootDir();
            String canonicalChroot = chroot.getCanonicalPath();
            String canonicalPath = file.getCanonicalPath();
            if (!canonicalPath.startsWith(canonicalChroot)) {
                Log.i(TAG, "Path violated folder restriction, denying");
                Log.d(TAG, "path: " + canonicalPath);
                Log.d(TAG, "chroot: " + chroot.toString());
                return true; // the path must begin with the chroot path
            }
            return false;
        } catch (Exception e) {
            Log.i(TAG, "Path canonicalization problem: " + e.toString());
            Log.i(TAG, "When checking file: " + file.getAbsolutePath());
            return true; // for security, assume violation
        }
    }
}
