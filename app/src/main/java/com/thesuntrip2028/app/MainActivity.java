package com.thesuntrip2028.app;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.location.GnssStatus;
import android.location.LocationManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.List;
import java.util.Locale;

import org.json.JSONObject;

import androidx.webkit.WebViewAssetLoader;

/**
 * The Sun Trip 2028 – demo version.
 * Shows the app (assets/index.html) full screen. Simulated data only:
 * GPS, Bluetooth to the bike and the real walkie-talkie come in the next version.
 */
public class MainActivity extends Activity {
    private static final int PICK_FILE = 1;
    private WebView web;
    private ValueCallback<Uri[]> pending;
    private TextToSpeech tts;
    private Talkie talkie;
    private GnssStatus.Callback gnssCb;
    private volatile int satUsed = 0, satView = 0;

    /** GPS detection: location on/off and satellites, for the page. */
    public class Gps {
        @JavascriptInterface
        public String status() {
            boolean en = false;
            try { en = ((LocationManager) getSystemService(Context.LOCATION_SERVICE)).isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Exception ignored) { }
            return "{\"enabled\":" + en + ",\"used\":" + satUsed + ",\"inView\":" + satView + "}";
        }
    }

    /** Remote control for the music app that is playing (QQ Music, etc.). */
    public class Media {
        private MediaController pick() {
            MediaSessionManager m = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> list = m.getActiveSessions(new ComponentName(MainActivity.this, MediaListener.class));
            MediaController best = null;
            for (MediaController c : list) {
                if (c.getPackageName().equals(getPackageName())) continue;
                if (best == null) best = c;
                PlaybackState ps = c.getPlaybackState();
                if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
            }
            return best;
        }

        @JavascriptInterface
        public String state() {
            try {
                MediaController c = pick();
                JSONObject o = new JSONObject();
                o.put("perm", true);
                if (c != null) {
                    o.put("app", c.getPackageName());
                    MediaMetadata md = c.getMetadata();
                    if (md != null) {
                        o.put("title", md.getString(MediaMetadata.METADATA_KEY_TITLE));
                        o.put("artist", md.getString(MediaMetadata.METADATA_KEY_ARTIST));
                    }
                    PlaybackState ps = c.getPlaybackState();
                    o.put("playing", ps != null && ps.getState() == PlaybackState.STATE_PLAYING);
                }
                return o.toString();
            } catch (SecurityException e) {
                return "{\"perm\":false}";
            } catch (Exception e) {
                return "{\"perm\":true}";
            }
        }

        @JavascriptInterface
        public void cmd(String what) {
            try {
                MediaController c = pick();
                if (c == null) return;
                MediaController.TransportControls t = c.getTransportControls();
                if ("play".equals(what)) t.play();
                else if ("pause".equals(what)) t.pause();
                else if ("next".equals(what)) t.skipToNext();
                else if ("prev".equals(what)) t.skipToPrevious();
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void openSettings() {
            try {
                Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) { }
        }
    }

    /**
     * Link to the bike's motor controller. Placeholder until the controller model is known:
     * it keeps the power the rider chose, and reports "not linked". The Bluetooth code that
     * sends this value to the controller goes in setPower().
     */
    public class Bike {
        private volatile int wanted = 200;
        @JavascriptInterface public void setPower(int watts) { wanted = Math.max(0, Math.min(350, watts)); }
        @JavascriptInterface public String status() { return "{\"linked\":false,\"set\":" + wanted + "}"; }
    }

    /** Walkie-talkie between phones on the same Wi-Fi / hotspot. */
    public class Talk {
        @JavascriptInterface
        public void start(String name) {
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO }, 3));
            }
            talkie.start(name);
        }
        @JavascriptInterface public void stop() { talkie.stop(); }
        @JavascriptInterface public void pttDown() { talkie.pttDown(); }
        @JavascriptInterface public void pttUp() { talkie.pttUp(); }
        @JavascriptInterface public String status() { return talkie.status(); }
    }

    private void startGnss() {
        if (Build.VERSION.SDK_INT < 24 || gnssCb != null) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            gnssCb = new GnssStatus.Callback() {
                @Override
                public void onSatelliteStatusChanged(GnssStatus st) {
                    int used = 0;
                    for (int i = 0; i < st.getSatelliteCount(); i++) if (st.usedInFix(i)) used++;
                    satUsed = used;
                    satView = st.getSatelliteCount();
                }
            };
            lm.registerGnssStatusCallback(gnssCb, new Handler(Looper.getMainLooper()));
        } catch (Exception e) {
            gnssCb = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        startGnss();
    }

    /** Lets the page speak navigation directions with the phone's own voice engine. */
    public class Voice {
        @JavascriptInterface
        public void speak(String text) {
            if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(Locale.UK);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    r = tts.setLanguage(Locale.US);
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.getDefault());
                }
            }
        });

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setGeolocationEnabled(true);
        // Serve the page from a secure https address so GPS (geolocation) is allowed
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            // allow the page to use the phone's GPS
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            // lets "Load GPX" and "Add songs" open the phone's file picker
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pending != null) pending.onReceiveValue(null);
                pending = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                String[] types = params.getAcceptTypes();
                boolean audio = types != null && types.length > 0 && types[0].startsWith("audio");
                i.setType(audio ? "audio/*" : "*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(i, PICK_FILE); }
                catch (Exception e) { pending = null; return false; }
                return true;
            }
        });
        talkie = new Talkie(this);
        web.addJavascriptInterface(new Voice(), "AndroidTTS");
        web.addJavascriptInterface(new Gps(), "AndroidGps");
        web.addJavascriptInterface(new Media(), "AndroidMedia");
        web.addJavascriptInterface(new Talk(), "AndroidTalk");
        web.addJavascriptInterface(new Bike(), "AndroidBike");
        setContentView(web);
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");

        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION }, 2);
        }
        startGnss();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_FILE || pending == null) { super.onActivityResult(requestCode, resultCode, data); return; }
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                result = new Uri[n];
                for (int k = 0; k < n; k++) result[k] = data.getClipData().getItemAt(k).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{ data.getData() };
            }
        }
        pending.onReceiveValue(result);
        pending = null;
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (talkie != null) talkie.stop();
        if (gnssCb != null && Build.VERSION.SDK_INT >= 24) { try { ((LocationManager) getSystemService(Context.LOCATION_SERVICE)).unregisterGnssStatusCallback(gnssCb); } catch (Exception ignored) { } }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
