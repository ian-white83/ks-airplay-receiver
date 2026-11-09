// SPDX-License-Identifier: GPL-3.0-or-later
package ks.airplay;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Remote control of the sender over DACP, as shairport-sync does: the sender names its remote in DACP-ID and
 * Active-Remote headers and advertises iTunes_Ctrl_<DACP-ID> as _dacp._tcp. A GET to /ctrl-int/1/<command>
 * with the Active-Remote header plays, pauses or skips.
 */
public final class DacpRemote {
    public static final String PLAY_PAUSE = "playpause", NEXT = "nextitem", PREVIOUS = "previtem";

    public final String dacpId, activeRemote;

    public DacpRemote(String dacpId, String activeRemote) {
        this.dacpId = dacpId;
        this.activeRemote = activeRemote;
    }

    /** The Bonjour instance name the sender advertises its remote under. */
    public String serviceName() {
        return "iTunes_Ctrl_" + dacpId;
    }

    /** Send a command to the remote at this address. Returns the HTTP status. */
    public int send(InetSocketAddress address, String command) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(address, 3000);
            s.setSoTimeout(5000);
            OutputStream out = s.getOutputStream();
            String host = address.getAddress().getHostAddress();
            if (host.indexOf(':') >= 0) host = "[" + host + "]";
            String req = "GET /ctrl-int/1/" + command + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + address.getPort() + "\r\n"
                    + "Active-Remote: " + activeRemote + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(req.getBytes(Util.UTF8));
            out.flush();
            String status = new BufferedReader(new InputStreamReader(s.getInputStream(), Util.UTF8)).readLine();
            if (status == null) throw new IOException("No answer from the sender's remote");
            String[] parts = status.split(" ");
            if (parts.length < 2) throw new IOException("Bad answer: " + status);
            return Integer.parseInt(parts[1]);
        }
    }
}
