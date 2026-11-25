package cl.vincentcalavera.renotas;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.ArrayList;

/** Muestra la app web de Renotas dentro de un WebView y conecta avisos y dictado con Android. */
public class MainActivity extends Activity {
    private static final int REQ_MIC = 1;
    private static final int REQ_NOTIF = 2;

    private WebView web;
    private SpeechRecognizer recognizer;
    private boolean wantListening = false;
    private boolean pendingStart = false;
    private String speechLang = "es-CL";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });
        String tab = getIntent() != null ? getIntent().getStringExtra("open_tab") : null;
        web.loadUrl("file:///android_asset/index.html"
                + (tab != null && tab.matches("[a-z]+") ? "#" + tab : ""));

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    // ---------- Puente entre la página y Android ----------
    private class Bridge {
        @JavascriptInterface
        public void syncReminders(final String json) {
            Reminders.sync(getApplicationContext(), json);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    askExactAlarmOnce(json);
                }
            });
        }

        @JavascriptInterface
        public String bubbleState() {
            return bubbleStateNow();
        }

        @JavascriptInterface
        public void setBubble(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    getSharedPreferences("renotas_app", Context.MODE_PRIVATE)
                            .edit().putBoolean("bubbleEnabled", on).apply();
                    if (!on) {
                        BubbleService.stop(MainActivity.this);
                    } else if (Settings.canDrawOverlays(MainActivity.this)) {
                        BubbleService.start(MainActivity.this);
                    } else {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception ignored) {
                        }
                    }
                    bubbleStateToJs();
                }
            });
        }

        @JavascriptInterface
        public void setPending(int rem, int over, int task, int shop) {
            SharedPreferences sp = getSharedPreferences("renotas_app", Context.MODE_PRIVATE);
            if (sp.getInt("pRem", -1) == rem && sp.getInt("pOver", -1) == over
                    && sp.getInt("pTask", -1) == task && sp.getInt("pShop", -1) == shop) return;
            sp.edit().putInt("pRem", rem).putInt("pOver", over)
                    .putInt("pTask", task).putInt("pShop", shop).apply();
            BubbleService.refresh(getApplicationContext());
        }

        @JavascriptInterface
        public boolean hasSpeech() {
            return SpeechRecognizer.isRecognitionAvailable(MainActivity.this);
        }

        @JavascriptInterface
        public void startSpeech(final String lang) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (lang != null && lang.length() > 1) speechLang = lang;
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                        pendingStart = true;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                        return;
                    }
                    wantListening = true;
                    startListening();
                }
            });
        }

        @JavascriptInterface
        public void stopSpeech() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    wantListening = false;
                    if (recognizer != null) recognizer.stopListening();
                    state("idle");
                }
            });
        }
    }

    private String bubbleStateNow() {
        boolean on = getSharedPreferences("renotas_app", Context.MODE_PRIVATE)
                .getBoolean("bubbleEnabled", false);
        if (!on) return "off";
        return Settings.canDrawOverlays(this) ? "on" : "needperm";
    }

    private void bubbleStateToJs() {
        js("window.onBubbleState&&window.onBubbleState('" + bubbleStateNow() + "')");
    }

    @Override
    protected void onResume() {
        super.onResume();
        BubbleService.setAppForeground(true);
        boolean on = getSharedPreferences("renotas_app", Context.MODE_PRIVATE)
                .getBoolean("bubbleEnabled", false);
        if (on && Settings.canDrawOverlays(this) && !BubbleService.running) {
            BubbleService.start(this);
        }
        bubbleStateToJs();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String tab = intent != null ? intent.getStringExtra("open_tab") : null;
        if (tab != null && tab.matches("[a-z]+")) {
            js("location.hash='" + tab + "'");
        }
    }

    private void askExactAlarmOnce(String json) {
        if (Build.VERSION.SDK_INT < 31 || "[]".equals(json)) return;
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am.canScheduleExactAlarms()) return;
        SharedPreferences sp = getSharedPreferences("renotas_app", Context.MODE_PRIVATE);
        if (sp.getBoolean("askedExact", false)) return;
        sp.edit().putBoolean("askedExact", true).apply();
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
        }
    }

    // ---------- Dictado con el reconocimiento de voz de Android ----------
    private void js(final String code) {
        web.post(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(code, null);
            }
        });
    }

    private void state(String st) {
        js("window.onNativeSpeechState&&window.onNativeSpeechState('" + st + "')");
    }

    private void deliver(Bundle r, boolean fin) {
        ArrayList<String> list = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return;
        js("window.onNativeSpeech&&window.onNativeSpeech(" + JSONObject.quote(list.get(0)) + "," + fin + ")");
    }

    private void restartSoon() {
        web.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (wantListening) startListening();
            }
        }, 250);
    }

    private void startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            state("unavailable");
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { state("listening"); }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onEvent(int eventType, Bundle params) { }

                @Override
                public void onError(int error) {
                    if (error == SpeechRecognizer.ERROR_NO_MATCH
                            || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        if (wantListening) restartSoon();
                        else state("idle");
                        return;
                    }
                    wantListening = false;
                    state(error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "denied" : "error");
                }

                @Override
                public void onResults(Bundle results) {
                    deliver(results, true);
                    if (wantListening) restartSoon();
                    else state("idle");
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    deliver(partialResults, false);
                }
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        recognizer.startListening(i);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (ok && pendingStart) {
                wantListening = true;
                startListening();
            } else {
                state("denied");
            }
            pendingStart = false;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        BubbleService.setAppForeground(false);
        if (wantListening) {
            wantListening = false;
            if (recognizer != null) recognizer.cancel();
            state("idle");
        }
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
