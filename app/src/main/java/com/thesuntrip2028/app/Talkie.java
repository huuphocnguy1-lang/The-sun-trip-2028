package com.thesuntrip2028.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Push-to-talk walkie-talkie between phones on the same Wi-Fi or phone hotspot.
 * No internet and no server: voice is sent as small UDP broadcast packets
 * (16 kHz, 16-bit mono, 20 ms per packet). Each phone also announces itself
 * every 2 seconds so the others can show who is online.
 */
public class Talkie {
    private static final int PORT = 50505;
    private static final int RATE = 16000;
    private static final int FRAME = 640; // 20 ms of 16-bit mono audio
    private static final byte HELLO = 1, VOICE = 2, END = 3;

    private final Context ctx;
    private final String id = Long.toHexString(new Random().nextLong());
    private final Map<String, Long> seen = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();

    private volatile boolean running = false;
    private volatile boolean txOn = false;
    private volatile String name = "Rider";
    private volatile String speakingId = null;
    private volatile long speakingAt = 0;
    private volatile boolean micDenied = false;

    private DatagramSocket sock;
    private AudioTrack track;
    private WifiManager.MulticastLock lock;
    private AudioFocusRequest focusReq;
    private boolean hasFocus = false;

    public Talkie(Context c) {
        ctx = c.getApplicationContext();
    }

    public synchronized void start(String n) {
        name = (n == null || n.trim().isEmpty()) ? "Rider" : n.trim();
        if (running) return;
        running = true;
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            lock = wm.createMulticastLock("suntrip-talk");
            lock.setReferenceCounted(false);
            lock.acquire();
        } catch (Exception ignored) { }
        new Thread(this::receiveLoop, "talk-rx").start();
        new Thread(this::helloLoop, "talk-hello").start();
    }

    public synchronized void stop() {
        running = false;
        txOn = false;
        try { if (sock != null) sock.close(); } catch (Exception ignored) { }
        sock = null;
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) { }
        try { if (track != null) { track.stop(); track.release(); } } catch (Exception ignored) { }
        track = null;
        releaseFocus();
        synchronized (seen) { seen.clear(); names.clear(); }
    }

    public void pttDown() {
        if (!running || txOn) return;
        txOn = true;
        new Thread(this::txLoop, "talk-tx").start();
    }

    public void pttUp() {
        txOn = false;
    }

    /** JSON for the page: network, microphone, online riders and who is talking. */
    public String status() {
        try {
            JSONObject o = new JSONObject();
            o.put("net", localNet());
            o.put("mic", !micDenied);
            long now = System.currentTimeMillis();
            boolean talkingNow = speakingId != null && now - speakingAt < 700;
            if (!talkingNow && speakingId != null) { speakingId = null; releaseFocus(); }
            JSONArray arr = new JSONArray();
            synchronized (seen) {
                for (Map.Entry<String, Long> e : new ArrayList<>(seen.entrySet())) {
                    if (now - e.getValue() > 7000) { seen.remove(e.getKey()); names.remove(e.getKey()); continue; }
                    JSONObject p = new JSONObject();
                    p.put("name", names.get(e.getKey()));
                    p.put("talking", talkingNow && e.getKey().equals(speakingId));
                    arr.put(p);
                }
                o.put("speaking", talkingNow ? names.get(speakingId) : "");
            }
            o.put("peers", arr);
            return o.toString();
        } catch (Exception e) {
            return "{\"net\":\"\",\"peers\":[],\"speaking\":\"\"}";
        }
    }

    // ---------- network ----------

    private synchronized DatagramSocket socket() throws Exception {
        if (sock == null || sock.isClosed()) {
            DatagramSocket s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.setBroadcast(true);
            s.bind(new InetSocketAddress(PORT));
            sock = s;
        }
        return sock;
    }

    private List<InetAddress> broadcasts() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null) out.add(b);
                }
            }
        } catch (Exception ignored) { }
        if (out.isEmpty()) {
            try { out.add(InetAddress.getByName("255.255.255.255")); } catch (Exception ignored) { }
        }
        return out;
    }

    private String localNet() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("dummy") || n.startsWith("tun")) continue; // mobile data / VPN
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress() && ia.getBroadcast() != null) {
                        boolean hotspot = n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("wlan1") || n.startsWith("softap");
                        return a.getHostAddress() + (hotspot ? " (your hotspot)" : " (Wi-Fi)");
                    }
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    private byte[] packet(byte type, byte[] payload, int len) {
        byte[] idb = id.getBytes(StandardCharsets.UTF_8);
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        if (nb.length > 60) nb = java.util.Arrays.copyOf(nb, 60);
        byte[] out = new byte[4 + idb.length + 1 + nb.length + len];
        int i = 0;
        out[i++] = 'S'; out[i++] = 'T'; out[i++] = type; out[i++] = (byte) idb.length;
        System.arraycopy(idb, 0, out, i, idb.length); i += idb.length;
        out[i++] = (byte) nb.length;
        System.arraycopy(nb, 0, out, i, nb.length); i += nb.length;
        if (len > 0) System.arraycopy(payload, 0, out, i, len);
        return out;
    }

    private void send(byte[] data) {
        try {
            DatagramSocket s = socket();
            for (InetAddress b : broadcasts()) {
                try { s.send(new DatagramPacket(data, data.length, b, PORT)); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }

    private void helloLoop() {
        while (running) {
            send(packet(HELLO, new byte[0], 0));
            try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[4096];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket().receive(p);
                int len = p.getLength();
                if (len < 6 || buf[0] != 'S' || buf[1] != 'T') continue;
                byte type = buf[2];
                int idLen = buf[3] & 0xff;
                if (4 + idLen + 1 > len) continue;
                String pid = new String(buf, 4, idLen, StandardCharsets.UTF_8);
                if (pid.equals(id)) continue; // our own broadcast
                int nLen = buf[4 + idLen] & 0xff;
                int off = 5 + idLen;
                if (off + nLen > len) continue;
                String pname = new String(buf, off, nLen, StandardCharsets.UTF_8);
                off += nLen;
                long now = System.currentTimeMillis();
                synchronized (seen) { seen.put(pid, now); names.put(pid, pname); }
                if (type == VOICE && !txOn) {
                    if (speakingId == null || speakingId.equals(pid) || now - speakingAt > 700) {
                        speakingId = pid;
                        speakingAt = now;
                        play(buf, off, len - off);
                    }
                } else if (type == END && pid.equals(speakingId)) {
                    speakingId = null;
                    releaseFocus();
                }
            } catch (Exception e) {
                if (!running) break;
                try { Thread.sleep(200); } catch (InterruptedException ie) { return; }
            }
        }
    }

    // ---------- audio ----------

    private void txLoop() {
        AudioRecord rec;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 4));
        } catch (SecurityException e) {
            micDenied = true; txOn = false; return;
        } catch (Exception e) {
            txOn = false; return;
        }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            micDenied = true; rec.release(); txOn = false; return;
        }
        micDenied = false;
        byte[] buf = new byte[FRAME];
        try {
            rec.startRecording();
            while (txOn && running) {
                int n = rec.read(buf, 0, FRAME);
                if (n > 0) send(packet(VOICE, buf, n));
            }
        } catch (Exception ignored) {
        } finally {
            try { rec.stop(); } catch (Exception ignored) { }
            rec.release();
        }
        send(packet(END, new byte[0], 0));
    }

    private synchronized void play(byte[] b, int off, int len) {
        if (len <= 0) return;
        if (track == null) {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    new AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    Math.max(min, FRAME * 8), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            track.play();
        }
        requestFocus();
        track.write(b, off, len);
    }

    /** Lower other music (QQ Music, etc.) while a rider is talking. */
    private void requestFocus() {
        if (hasFocus) return;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .build();
                am.requestAudioFocus(focusReq);
            } else {
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
            hasFocus = true;
        } catch (Exception ignored) { }
    }

    private void releaseFocus() {
        if (!hasFocus) return;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && focusReq != null) am.abandonAudioFocusRequest(focusReq);
            else am.abandonAudioFocus(null);
        } catch (Exception ignored) { }
        hasFocus = false;
    }
}
