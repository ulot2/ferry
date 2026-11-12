package app.ferry;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A session: one encrypted connection that stays open while a feature is in use (Helm). As for files, the phone
 * listens and the laptop connects (Lan). The laptop (desktop/Session.cs) must use the same wire format:
 * the laptop sends the hello of Lan; then each side sends frames: a 4-byte big-endian length, then the frame sealed
 * with Crypto.sealSession (one byte for the kind of message, then the body). Each side numbers its own frames from 0.
 * Both sides send a ping every 5 s, and 15 s without a frame ends the session.
 */
final class Session implements Closeable {
    static final byte PING = 0;
    private static final byte FROM_PHONE = 0, TO_PHONE = 1;
    private static final int MAX_BODY = 64 * 1024;
    private final Socket socket;
    private final String code;
    private final byte[] id;
    private final DataOutputStream out;
    /** One thread writes, so frames go out in the order of their numbers. */
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor();
    private long sent, got;
    private volatile boolean closed;

    /**
     * Listens, tells the laptop through ntfy.sh, and waits up to timeoutMs for it. Blocks: call it off the main thread.
     * Returns null when this phone has no Wi-Fi address, or the laptop does not come (or says no).
     * Throws when the message to the laptop does not go out (offline).
     */
    static Session offer(Context c, String what, int timeoutMs) throws IOException {
        List<String> ips = Lan.addresses(c);
        if (ips.isEmpty()) return null;
        String code = Ferry.code(c);
        byte[] id = Crypto.newId(16);
        String key = Lan.hex(id);
        ServerSocket server = Lan.listen(key);
        try {
            Ferry.postLan(c, new JSONObject().put("t", "session").put("id", key).put("for", what)
                    .put("port", server.getLocalPort()).put("ips", new JSONArray(ips)));
        } catch (IOException | JSONException e) {
            Lan.cancel(key);
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
        Socket s = Lan.accept(server, key, code, id, timeoutMs);
        if (s == null) return null;
        try {
            s.setTcpNoDelay(true);   // each message goes at once: a pointer move cannot wait for the next one
            s.setSoTimeout(15_000);   // the laptop pings every 5 s
            return new Session(s, code, id);
        } catch (IOException e) {
            s.close();
            throw e;
        }
    }

    private Session(Socket s, String code, byte[] id) throws IOException {
        socket = s;
        this.code = code;
        this.id = id;
        out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        writer.scheduleWithFixedDelay(() -> write(PING, new byte[0]), 0, 5, TimeUnit.SECONDS);
    }

    /** Queues a message for the laptop. Never blocks, so the main thread can call it. A failed write ends the session. */
    void send(byte kind, byte[] body) {
        if (closed) return;
        try {
            writer.execute(() -> write(kind, body));
        } catch (RejectedExecutionException e) {
            // ended meanwhile
        }
    }

    private void write(byte kind, byte[] body) {
        try {
            byte[] plain = new byte[1 + body.length];
            plain[0] = kind;
            System.arraycopy(body, 0, plain, 1, body.length);
            byte[] sealed = Crypto.sealSession(code, id, FROM_PHONE, sent++, plain);
            out.writeInt(sealed.length);
            out.write(sealed);
            out.flush();
        } catch (IOException | GeneralSecurityException e) {
            close();
        }
    }

    /** Reads the laptop's frames until the session ends. Blocks: run it on its own thread. */
    void read() {
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            while (!closed) {
                int n = in.readInt();
                if (n < 12 + 16 + 1 || n > 12 + 16 + 1 + MAX_BODY) break;   // not a frame of ours
                byte[] sealed = new byte[n];
                in.readFully(sealed);
                // For Helm the laptop only pings: any frame that opens shows that it is still there.
                if (Crypto.openSession(code, id, TO_PHONE, got++, sealed) == null) break;   // changed, repeated or out of order
            }
        } catch (IOException e) {
            // The laptop closed the session, the Wi-Fi dropped, 15 s of silence, or close().
        }
        close();
    }

    boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        writer.shutdownNow();
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
