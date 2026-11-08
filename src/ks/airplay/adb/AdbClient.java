// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay.adb;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import ks.airplay.Util;

/**
 * Just enough of the ADB wire protocol to run a command on this device's own adbd over TCP: connect, sign in with
 * an RSA key, open "shell:" and "root:" services and read what they print. The protocol is documented in AOSP
 * packages/modules/adb/protocol.txt.
 */
public final class AdbClient implements Closeable {
    static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f, CLSE = 0x45534c43,
            WRTE = 0x45545257, STLS = 0x534c5453;
    static final int VERSION = 0x01000000, MAX_DATA = 4096;
    static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;

    /** Thrown when adbd will not accept the key, so retrying cannot help until someone approves it. */
    public static final class NotApproved extends IOException {
        NotApproved(String message) { super(message); }
    }

    public interface Approval {
        /** adbd showed the "Allow USB debugging?" prompt on the screen and waits for an answer. */
        void waiting();
    }

    static final class Message {
        final int command, arg0, arg1;
        final byte[] data;
        Message(int command, int arg0, int arg1, byte[] data) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;

    private AdbClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    /**
     * Connect and authenticate. When adbd does not know the key yet it shows the approval prompt; this then waits
     * up to approvalMs for someone to tap Allow.
     */
    public static AdbClient connect(String host, int port, AdbKey key, int approvalMs, Approval approval) throws IOException {
        Socket s = new Socket();
        AdbClient c = null;
        try {
            s.connect(new InetSocketAddress(host, port), 3000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(10000);
            c = new AdbClient(s);
            c.handshake(key, approvalMs, approval);
            return c;
        } catch (IOException | RuntimeException e) {
            if (c != null) c.close();
            else s.close();
            throw e;
        }
    }

    private void handshake(AdbKey key, int approvalMs, Approval approval) throws IOException {
        send(CNXN, VERSION, MAX_DATA, "host::ks-airplay\0".getBytes(Util.UTF8));
        boolean signed = false, offered = false;
        while (true) {
            Message m;
            try {
                m = read();
            } catch (SocketTimeoutException e) {
                if (offered) throw new NotApproved("Nobody allowed USB debugging for this plugin on the kiosk screen");
                throw e;
            } catch (EOFException e) {
                if (offered) throw new NotApproved("The kiosk refused this plugin's ADB key");
                throw new IOException("adbd closed the connection while signing in", e);
            }
            if (m.command == CNXN) {
                socket.setSoTimeout(10000);
                return;
            }
            if (m.command == STLS) throw new IOException("This ADB port only takes TLS (wireless debugging pairing). Use classic ADB over network on port 5555.");
            if (m.command != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("Unexpected ADB reply " + name(m.command));
            if (!signed) {
                signed = true;
                send(AUTH, AUTH_SIGNATURE, 0, key.sign(m.data));
            } else if (!offered) {
                offered = true;
                send(AUTH, AUTH_RSAPUBLICKEY, 0, (key.publicKey() + "\0").getBytes(Util.UTF8));
                socket.setSoTimeout(Math.max(1000, approvalMs));
                if (approval != null) approval.waiting();
            } else {
                throw new NotApproved("The kiosk refused this plugin's ADB key");
            }
        }
    }

    /** Run a shell command and return what it prints. */
    public String shell(String command, int timeoutMs) throws IOException {
        return service("shell:" + command, timeoutMs);
    }

    /** Ask adbd to restart as root. It answers with a line of text, then restarts and drops this connection. */
    public String root() throws IOException {
        return service("root:", 10000);
    }

    private String service(String name, int timeoutMs) throws IOException {
        int local = nextLocalId++;
        int remote = 0;
        byte[] request = (name + "\0").getBytes(Util.UTF8);
        if (request.length > MAX_DATA) throw new IOException("Command too long for ADB (" + request.length + " bytes)");
        send(OPEN, local, 0, request);
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            int left = (int) (deadline - System.currentTimeMillis());
            if (left <= 0) throw new SocketTimeoutException("ADB command timed out: " + text.toString("UTF-8").trim());
            socket.setSoTimeout(left);
            Message m;
            try {
                m = read();
            } catch (EOFException e) {
                return text.toString("UTF-8"); // adbd restarting, as after root:
            } catch (IOException e) {
                if (remote != 0 && !(e instanceof SocketTimeoutException)) return text.toString("UTF-8"); // reset while restarting
                throw e;
            }
            if (m.arg1 != local && m.command != CNXN) continue; // another stream
            if (m.command == OKAY) {
                remote = m.arg0;
            } else if (m.command == WRTE) {
                remote = m.arg0;
                text.write(m.data, 0, m.data.length);
                send(OKAY, local, remote, new byte[0]);
            } else if (m.command == CLSE) {
                if (remote == 0) throw new IOException("adbd refused " + name.substring(0, name.indexOf(':') + 1));
                send(CLSE, local, remote, new byte[0]);
                return text.toString("UTF-8");
            }
        }
    }

    void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : data) sum += x & 0xff;
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~command).put(data);
        out.write(b.array());
        out.flush();
    }

    Message read() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        int command = b.getInt(), arg0 = b.getInt(), arg1 = b.getInt(), length = b.getInt();
        b.getInt();
        if (b.getInt() != ~command) throw new IOException("Bad ADB message header");
        if (length < 0 || length > 1 << 20) throw new IOException("Bad ADB message length " + length);
        byte[] data = new byte[length];
        in.readFully(data);
        return new Message(command, arg0, arg1, data);
    }

    static String name(int command) {
        return new String(new byte[] {(byte) command, (byte) (command >> 8), (byte) (command >> 16), (byte) (command >> 24)}, Util.UTF8);
    }

    @Override public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
