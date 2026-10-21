package com.pedro.farmandoaura;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int PICK_FILE = 1;
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF15171B);
        getWindow().setNavigationBarColor(0xFF15171B);

        web = new WebView(this);
        web.setBackgroundColor(0xFF15171B);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Escolha o backup"), PICK_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(this), "AuraAndroid");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            result = new Uri[]{ data.getData() };
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    static class Bridge {
        private final Context ctx;
        Bridge(Context c) { ctx = c.getApplicationContext(); }

        @JavascriptInterface
        public String saveBackup(String name, String json) {
            try {
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return "erro";
                    try (OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
                        out.write(bytes);
                    }
                } else {
                    File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) return "erro";
                    try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
                        out.write(bytes);
                    }
                }
                return "ok";
            } catch (Exception e) {
                return "erro";
            }
        }

        @JavascriptInterface
        public String version() {
            try {
                return String.valueOf(ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode);
            } catch (Exception e) {
                return "0";
            }
        }

        @JavascriptInterface
        public String downloadUpdate(String url) {
            try {
                final DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm == null) return "erro";
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                r.setTitle("Farmando Aura");
                r.setDescription("Baixando a nova versão");
                r.setMimeType("application/vnd.android.package-archive");
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "farmando-aura-" + System.currentTimeMillis() + ".apk");
                final long id = dm.enqueue(r);
                BroadcastReceiver done = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return;
                        try { ctx.unregisterReceiver(this); } catch (Exception ignored) {}
                        Uri apk = dm.getUriForDownloadedFile(id);
                        if (apk == null) return;
                        Intent install = new Intent(Intent.ACTION_VIEW);
                        install.setDataAndType(apk, "application/vnd.android.package-archive");
                        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        try { ctx.startActivity(install); } catch (Exception ignored) {}
                    }
                };
                IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
                if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(done, f, Context.RECEIVER_EXPORTED);
                else ctx.registerReceiver(done, f);
                return "ok";
            } catch (Exception e) {
                return "erro";
            }
        }

        @JavascriptInterface
        public void vibrate(String patternJson) {
            try {
                String[] parts = patternJson.replace("[", "").replace("]", "").split(",");
                long[] p = new long[parts.length + 1];
                p[0] = 0;
                for (int i = 0; i < parts.length; i++) p[i + 1] = Long.parseLong(parts[i].trim());
                Vibrator vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
                if (vib == null) return;
                if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createWaveform(p, -1));
                else vib.vibrate(p, -1);
            } catch (Exception ignored) {}
        }
    }
}
