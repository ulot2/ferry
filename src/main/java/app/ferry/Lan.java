package app.ferry;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

/**
 * Direct transfer over Wi-Fi, for files of any size. The phone always listens and the laptop always connects,
 * so Windows Firewall never asks. ntfy.sh carries only the short sealed messages that set it up.
 * The laptop (desktop/Lan.cs) must use the same wire format:
 * the laptop sends a hello (4-byte length, then the sealed transfer id); then the sender sends frames
 * (4-byte header: top bit set on the last frame, the rest the sealed length; then the frame, at most 1 MB sealed);
 * then the receiver answers one byte, 1, when it has all of it. Every number is big-endian.
 */
final class Lan {
    static final int FRAME = 1024 * 1024;
    /** Big socket buffers keep the Wi-Fi busy while the next frame is read and sealed. */
    private static final int BUFFER = 4 * 1024 * 1024;
    private static final Map<String, ServerSocket> LISTENING = new ConcurrentHashMap<>();

    /** This phone's addresses on Wi-Fi, a hotspot, USB or Ethernet. Mobile data is left out: the laptop cannot reach it. */
    @SuppressWarnings("deprecation")   // getAllNetworks: its replacement only reports networks one at a time, by callback
    static List<String> addresses(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        Set<String> cellular = new HashSet<>();
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            LinkProperties link = cm.getLinkProperties(n);
            if (caps != null && link != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cellular.add(link.getInterfaceName());
        }
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || cellular.contains(ni.getName())) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add(a.getHostAddress());
            }
        } catch (SocketException e) {
            // No list of interfaces: no direct transfer, ntfy.sh still works.
        }
        return out;
    }

    static ServerSocket listen(String id) throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReceiveBufferSize(BUFFER);   // before bind, so the TCP window can grow
        server.bind(new InetSocketAddress(0));
        LISTENING.put(id, server);
        return server;
    }

    /** The laptop said it cannot connect: stop waiting for it at once. */
    static void cancel(String id) {
        ServerSocket server = LISTENING.remove(id);
        if (server != null) close(server);
    }

    /**
     * Waits for the laptop and checks its hello. Others on the network that connect are dropped.
     * Returns null when the laptop does not come in time, or when cancel() stops the wait.
     */
    static Socket accept(ServerSocket server, String id, String code, byte[] idBytes, int timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        try {
            while (true) {
                int left = (int) (end - SystemClock.uptimeMillis());
                if (left <= 0) return null;
                server.setSoTimeout(left);
                Socket s = server.accept();
                try {
                    s.setSoTimeout(30_000);   // a stalled Wi-Fi link fails the transfer instead of hanging it
                    s.setSendBufferSize(BUFFER);
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    int n = in.readInt();
                    if (n > 0 && n <= 256) {
                        byte[] hello = new byte[n];
                        in.readFully(hello);
                        if (Crypto.openHello(code, hello, idBytes)) return s;
                    }
                } catch (IOException e) {
                    // not our laptop
                }
                close(s);
            }
        } catch (IOException e) {
            return null;   // timed out, or cancel() closed the server
        } finally {
            LISTENING.remove(id);
            close(server);
        }
    }

    /**
     * Sends a file straight to the laptop. Returns false when the laptop did not connect (another network,
     * or an older Ferry); then nothing was sent. Throws when the transfer broke off.
     */
    static boolean offer(Context c, String name, InputStream file, long size, IntConsumer progress) throws IOException {
        List<String> ips = addresses(c);
        if (ips.isEmpty()) return false;
        String code = Ferry.code(c);
        byte[] id = Crypto.newId(16);
        String key = hex(id);
        ServerSocket server = listen(key);
        try {
            Ferry.postLan(c, "offer", key, name, size, server.getLocalPort(), ips);
        } catch (IOException e) {
            cancel(key);
            throw e;
        }
        Socket s = accept(server, key, code, id, 10_000);
        if (s == null) return false;
        try {
            send(s, code, id, file, size, progress);
        } finally {
            close(s);
        }
        return true;
    }

    /** Frames out of the file. Reads one frame ahead, because the last frame must say that it is the last. */
    static void send(Socket s, String code, byte[] id, InputStream file, long size, IntConsumer progress) throws IOException {
        DataOutputStream out = new DataOutputStream(s.getOutputStream());
        byte[] buf = new byte[FRAME], next = new byte[FRAME];
        int n = readFull(file, buf);
        long sent = 0;
        try {
            for (long i = 0; ; i++) {
                int m = n == FRAME ? readFull(file, next) : 0;
                boolean last = m == 0;
                byte[] sealed = Crypto.sealFrame(code, id, i, last, n == FRAME ? buf : Arrays.copyOf(buf, n));
                out.writeInt(sealed.length | (last ? 0x80000000 : 0));
                out.write(sealed);
                sent += n;
                if (size > 0) progress.accept((int) Math.min(100, 100 * sent / size));
                if (last) break;
                byte[] t = buf;
                buf = next;
                next = t;
                n = m;
            }
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
        out.flush();
        if (s.getInputStream().read() != 1) throw new IOException("The laptop did not confirm the file");
    }

    /** Frames into out. Throws on a frame that does not open, so a changed or cut-off file is never kept. */
    static void receive(Socket s, String code, byte[] id, OutputStream out, long size, IntConsumer progress) throws IOException {
        DataInputStream in = new DataInputStream(s.getInputStream());
        long got = 0;
        for (long i = 0; ; i++) {
            int header = in.readInt();
            boolean last = header < 0;
            int n = header & 0x7FFFFFFF;
            if (n > FRAME + 28) throw new IOException("Frame too large");
            byte[] sealed = new byte[n];
            in.readFully(sealed);
            byte[] plain = Crypto.openFrame(code, id, i, last, sealed);
            if (plain == null) throw new IOException("A frame did not open");
            out.write(plain);
            got += plain.length;
            if (size > 0) progress.accept((int) Math.min(100, 100 * got / size));
            if (last) break;
        }
        out.flush();
        s.getOutputStream().write(1);
    }

    private static int readFull(InputStream in, byte[] buf) throws IOException {
        int n = 0;
        for (int r; n < buf.length && (r = in.read(buf, n, buf.length - n)) != -1; ) n += r;
        return n;
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }

    /** The 16-byte transfer id from its hex form, or null if it is not one. */
    static byte[] unhex(String s) {
        if (s == null || !s.matches("[0-9a-f]{32}")) return null;
        byte[] b = new byte[16];
        for (int i = 0; i < 16; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    private static void close(java.io.Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    private Lan() {}
}
