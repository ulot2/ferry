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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A session: one encrypted connection that stays open while a feature is in use (Helm, Phone photos). As for files,
 * the phone listens and the laptop connects (Lan). The laptop (desktop/Session.cs) must use the same wire format:
 * the laptop sends the hello of Lan; then each side sends frames: a 4-byte big-endian length, then the frame sealed
 * with Crypto.sealSession (one byte for the kind of message, then the body). Each side numbers its own frames from 0.
 * Both sides send a ping every 5 s, and 15 s without a frame ends the session.
 * <p>
 * The phone starts a session with a "session" message through ntfy.sh (Helm). The laptop asks for one with "open";
 * the phone answers with "session", or with "session" and a "why" when it says no (Phone photos).
 */
final class Session implements Closeable {
    static final byte PING = 0;
    private static final byte FROM_PHONE = 0, TO_PHONE = 1;
    static final int MAX_BODY = 64 * 1024;
    private final Socket socket;
    private final String code;
    private final byte[] id;
    private final DataOutputStream out;
    /** One thread writes, so frames go out in the order of their numbers. */
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor();
    private long sent, got;
    private volatile boolean closed;

    /** What to do with each message from the laptop. Runs on the thread that called read(). */
    interface Handler {
        void message(byte kind, byte[] body) throws IOException;
    }

    /**
     * Starts a session from the phone: listens, tells the laptop through ntfy.sh, and waits up to timeoutMs for it.
     * Blocks: call it off the main thread. Returns null when this phone has no Wi-Fi address, or the laptop does not
     * come (or says no). Throws when the message to the laptop does not go out (offline).
     */
    static Session offer(Context c, String what, int timeoutMs) throws IOException {
        if (Lan.addresses(c).isEmpty()) return null;
        return listen(c, Crypto.newId(16), what, timeoutMs);
    }

    /**
     * Answers an "open" from the laptop. With why set ("off", "permission"), it tells the laptop why not and returns null.
     * Otherwise it listens and waits up to timeoutMs for the laptop. Blocks: call it off the main thread.
     */
    static Session answer(Context c, byte[] id, String what, String why, int timeoutMs) throws IOException {
        if (why == null && Lan.addresses(c).isEmpty()) why = "nowifi";
        if (why == null) return listen(c, id, what, timeoutMs);
        try {
            Ferry.postLan(c, message(id, what).put("why", why).put("port", 0).put("ips", new JSONArray()));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return null;
    }

    private static Session listen(Context c, byte[] id, String what, int timeoutMs) throws IOException {
        List<String> ips = Lan.addresses(c);
        String code = Ferry.code(c);
        String key = Lan.hex(id);
        ServerSocket server = Lan.listen(key);
        try {
            Ferry.postLan(c, message(id, what).put("port", server.getLocalPort()).put("ips", new JSONArray(ips)));
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

    private static JSONObject message(byte[] id, String what) throws JSONException {
        return new JSONObject().put("t", "session").put("id", Lan.hex(id)).put("for", what);
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

    /** Sends a message and waits until it is on its way, so a large photo goes part by part instead of all into memory. */
    void sendAndWait(byte kind, byte[] body) throws IOException {
        try {
            writer.submit(() -> write(kind, body)).get(30, TimeUnit.SECONDS);
        } catch (RejectedExecutionException | ExecutionException | TimeoutException e) {
            close();
            throw new IOException("The session ended", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        if (closed) throw new IOException("The session ended");
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

    /**
     * Reads the laptop's frames until the session ends, and hands each message to handler (null: pings only, as for Helm).
     * Blocks: run it on its own thread.
     */
    void read(Handler handler) {
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            while (!closed) {
                int n = in.readInt();
                if (n < 12 + 16 + 1 || n > 12 + 16 + 1 + MAX_BODY) break;   // not a frame of ours
                byte[] sealed = new byte[n];
                in.readFully(sealed);
                byte[] plain = Crypto.openSession(code, id, TO_PHONE, got++, sealed);
                if (plain == null) break;   // changed, repeated or out of order
                if (plain[0] != PING && handler != null) handler.message(plain[0], Arrays.copyOfRange(plain, 1, plain.length));
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
