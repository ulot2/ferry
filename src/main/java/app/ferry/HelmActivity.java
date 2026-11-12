package app.ferry;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Helm: the phone as a remote for the laptop, over a session on the same Wi-Fi (Session.java). The session opens
 * when this screen shows and ends when it goes away. desktop/Helm.cs must use the same message kinds and bodies.
 */
public class HelmActivity extends Activity {
    // Message kinds. Bodies: MOVE = dx, dy (2 bytes each, signed); BUTTON = 0 left or 1 right, then 1 down or 0 up;
    // SCROLL = vertical, horizontal (2 bytes each, signed, 120 = one wheel notch); TEXT = UTF-8; KEY = a Windows virtual-key code.
    private static final byte MOVE = 1, BUTTON = 2, SCROLL = 3, TEXT = 4, KEY = 5, LOCK = 6, SLEEP = 7;
    // Windows virtual-key codes. The laptop takes only these.
    private static final int VK_BACK = 0x08, VK_TAB = 0x09, VK_RETURN = 0x0D, VK_ESCAPE = 0x1B, VK_PRIOR = 0x21, VK_NEXT = 0x22,
            VK_LEFT = 0x25, VK_UP = 0x26, VK_RIGHT = 0x27, VK_DOWN = 0x28, VK_MUTE = 0xAD, VK_VOLUME_DOWN = 0xAE, VK_VOLUME_UP = 0xAF,
            VK_MEDIA_NEXT = 0xB0, VK_MEDIA_PREV = 0xB1, VK_MEDIA_PLAY = 0xB3;
    private static final int TEXT_CHUNK = 8000;   // characters per message: well under the 64 KB limit of a session frame
    private volatile Session session;
    private int attempt;   // counts each try to connect; an answer to an older try is closed
    private boolean started, clicker;
    private String typed = "";   // what the type field holds on the laptop, so a change sends only the difference
    private EditText field;
    private View lamp, notice, padMode, clickMode;
    private TextView statusText, noticeBody;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Configuration theme = Ferry.themeOverride(base);
        if (theme != null) applyOverrideConfiguration(theme);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!Ferry.paired(this)) {
            Toast.makeText(this, "Pair Ferry with your laptop first.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setContentView(R.layout.activity_helm);
        lamp = findViewById(R.id.lamp);
        statusText = findViewById(R.id.statusText);
        notice = findViewById(R.id.notice);
        noticeBody = findViewById(R.id.noticeBody);
        padMode = findViewById(R.id.padMode);
        clickMode = findViewById(R.id.clickMode);
        field = findViewById(R.id.type);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.again).setOnClickListener(v -> connect());

        RadioGroup modes = findViewById(R.id.modeGroup);
        clicker = Ferry.prefs(this).getBoolean("helm_clicker", false);
        modes.check(clicker ? R.id.modeClicker : R.id.modePad);
        showMode();
        modes.setOnCheckedChangeListener((g, id) -> {
            clicker = id == R.id.modeClicker;
            Ferry.prefs(this).edit().putBoolean("helm_clicker", clicker).apply();
            showMode();
        });

        Trackpad pad = findViewById(R.id.pad);
        pad.setSink(new Trackpad.Sink() {
            @Override
            public void move(int dx, int dy) {
                send(MOVE, pair(dx, dy));
            }

            @Override
            public void button(int which, boolean down) {
                send(BUTTON, new byte[]{(byte) which, (byte) (down ? 1 : 0)});
            }

            @Override
            public void scroll(int v, int h) {
                send(SCROLL, pair(v, h));
            }
        });
        // Speed 0.5 to 5 in steps of 0.25; 2 at first.
        SeekBar speed = findViewById(R.id.speed);
        speed.setProgress(Ferry.prefs(this).getInt("helm_speed", 6));
        pad.setSpeed(0.5f + speed.getProgress() * 0.25f);
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                pad.setSpeed(0.5f + progress * 0.25f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                Ferry.prefs(HelmActivity.this).edit().putInt("helm_speed", s.getProgress()).apply();
            }
        });

        bindKey(R.id.keyEsc, VK_ESCAPE);
        bindKey(R.id.keyTab, VK_TAB);
        bindKey(R.id.keyEnter, VK_RETURN);
        bindKey(R.id.keyLeft, VK_LEFT);
        bindKey(R.id.keyUp, VK_UP);
        bindKey(R.id.keyDown, VK_DOWN);
        bindKey(R.id.keyRight, VK_RIGHT);
        findViewById(R.id.keyBack).setOnClickListener(v -> backspace());
        // The media keys do not touch the type field.
        bindMedia(R.id.mediaPrev, VK_MEDIA_PREV);
        bindMedia(R.id.mediaPlay, VK_MEDIA_PLAY);
        bindMedia(R.id.mediaNext, VK_MEDIA_NEXT);
        bindMedia(R.id.volDown, VK_VOLUME_DOWN);
        bindMedia(R.id.volMute, VK_MUTE);
        bindMedia(R.id.volUp, VK_VOLUME_UP);
        bindMedia(R.id.slideNext, VK_NEXT);
        bindMedia(R.id.slidePrev, VK_PRIOR);

        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                typedChanged(s.toString());
            }
        });
        field.setOnEditorActionListener((v, action, e) -> {
            if (e == null || e.getAction() == KeyEvent.ACTION_DOWN) key(VK_RETURN);   // a hardware Enter comes as down and up: one press
            return true;
        });
        // Some keyboards send Backspace as a key when the field is empty; the laptop then deletes what is before its cursor.
        field.setOnKeyListener((v, code, e) -> {
            if (code != KeyEvent.KEYCODE_DEL || e.getAction() != KeyEvent.ACTION_DOWN || field.length() > 0) return false;
            sendKey(VK_BACK);
            return true;
        });

        findViewById(R.id.lock).setOnClickListener(v -> send(LOCK, new byte[0]));
        findViewById(R.id.sleep).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Put " + laptop() + " to sleep?")
                .setMessage("The phone cannot wake it. To wake it, press a key or the power button on the laptop.")
                .setPositiveButton("Sleep", (d, w) -> send(SLEEP, new byte[0]))
                .setNegativeButton("Cancel", null)
                .show());
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        connect();
    }

    /** The session ends when this screen goes away, so the laptop is never controlled from a phone in a pocket. */
    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        attempt++;
        Session s = session;
        session = null;
        if (s != null) s.close();
    }

    private void connect() {
        int mine = ++attempt;
        if (Lan.addresses(this).isEmpty()) {
            show(false, "Connect this phone to the same Wi-Fi as " + laptop() + ". Helm works only on the same network.");
            return;
        }
        notice.setVisibility(View.GONE);
        lamp.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.ink_muted)));
        statusText.setText("Connecting");
        new Thread(() -> {
            Session s = null;
            String problem = null;
            try {
                s = Session.offer(this, "helm", 60_000);   // the first time, the laptop asks its user: give them time
            } catch (IOException e) {
                problem = "Ferry could not reach ntfy.sh to find " + laptop() + ". Make sure that the phone is online. Helm needs the internet to start.";
            }
            Session got = s;
            String who = laptop();
            String why = problem != null ? problem : Character.toUpperCase(who.charAt(0)) + who.substring(1) + " did not connect. Make sure that both are on the same Wi-Fi and that Ferry runs on the laptop. "
                    + "The first time, allow Helm on the laptop. Later, it is \"Let the phone control this laptop\" in Ferry Settings on the laptop.";
            runOnUiThread(() -> {
                if (mine != attempt || !started) {
                    if (got != null) got.close();
                    return;
                }
                if (got == null) {
                    show(false, why);
                    return;
                }
                session = got;
                clearField();
                show(true, null);
                new Thread(() -> {
                    got.read(null);
                    runOnUiThread(() -> {
                        if (session != got) return;
                        session = null;
                        show(false, "The link to " + laptop() + " ended. The laptop went to sleep, closed Helm, or left the Wi-Fi.");
                    });
                }, "ferry-helm").start();
            });
        }, "ferry-helm-connect").start();
    }

    private void show(boolean connected, String problem) {
        lamp.setBackgroundTintList(ColorStateList.valueOf(getColor(connected ? R.color.steady : R.color.adrift)));
        statusText.setText(connected ? "Steady link" : "Not connected");
        notice.setVisibility(connected ? View.GONE : View.VISIBLE);
        if (problem != null) noticeBody.setText(problem);
    }

    private void showMode() {
        padMode.setVisibility(clicker ? View.GONE : View.VISIBLE);
        clickMode.setVisibility(clicker ? View.VISIBLE : View.GONE);
    }

    private void send(byte kind, byte[] body) {
        Session s = session;
        if (s != null) s.send(kind, body);
    }

    private void sendKey(int vk) {
        send(KEY, new byte[]{(byte) vk});
    }

    /** A key from the key rows. The laptop's cursor moves, so the type field starts again empty. */
    private void key(int vk) {
        clearField();
        sendKey(vk);
    }

    private void bindKey(int id, int vk) {
        findViewById(id).setOnClickListener(v -> key(vk));
    }

    private void bindMedia(int id, int vk) {
        findViewById(id).setOnClickListener(v -> sendKey(vk));
    }

    /** Deletes the last character of the type field (the laptop follows), or, when it is empty, presses Backspace on the laptop. */
    private void backspace() {
        int n = field.length();
        if (n == 0) {
            sendKey(VK_BACK);
            return;
        }
        field.getText().delete(n - Character.charCount(Character.codePointBefore(field.getText(), n)), n);
    }

    private void clearField() {
        typed = "";
        field.setText("");   // the watcher sees no difference, so nothing goes to the laptop
    }

    /**
     * The type field changed: the keyboard typed, deleted, or corrected a word. The laptop gets Backspace for each
     * character after the part that stayed the same, then the new characters.
     */
    private void typedChanged(String now) {
        int same = 0, max = Math.min(typed.length(), now.length());
        while (same < max && typed.charAt(same) == now.charAt(same)) same++;
        if (same > 0 && Character.isHighSurrogate(typed.charAt(same - 1))) same--;   // never split an emoji
        for (int i = typed.codePointCount(same, typed.length()); i > 0; i--) sendKey(VK_BACK);
        for (int at = same; at < now.length(); ) {
            int end = Math.min(now.length(), at + TEXT_CHUNK);
            if (end < now.length() && Character.isHighSurrogate(now.charAt(end - 1))) end--;
            send(TEXT, now.substring(at, end).getBytes(StandardCharsets.UTF_8));
            at = end;
        }
        typed = now;
    }

    /** While connected, the volume buttons go to the laptop: its volume, or the slide in clicker mode. */
    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (session == null || (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN)) return super.onKeyDown(code, e);
        boolean up = code == KeyEvent.KEYCODE_VOLUME_UP;
        sendKey(clicker ? (up ? VK_NEXT : VK_PRIOR) : (up ? VK_VOLUME_UP : VK_VOLUME_DOWN));
        return true;
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (session != null && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) return true;
        return super.onKeyUp(code, e);
    }

    /** Two signed 2-byte numbers, big-endian, each clamped to its range. */
    private static byte[] pair(int a, int b) {
        return ByteBuffer.allocate(4).putShort(clamp(a)).putShort(clamp(b)).array();
    }

    private static short clamp(int v) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
    }

    private String laptop() {
        String peer = Ferry.prefs(this).getString("peer", "");
        return peer.isEmpty() ? "the laptop" : peer;
    }
}
