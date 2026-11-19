package tw.eddiehsu.meetingnotes;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;
import android.widget.Toast;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

import java.io.File;

/** 由編譯流程覆蓋 `npx cap add android` 產生的 MainActivity，註冊 App 自己的原生外掛。 */
public class MainActivity extends BridgeActivity {
    private static final String TAG = "MeetingNotes";
    // 畫面元件（Android System WebView）當掉的時間，跨 Activity 重建保留
    private static long goneAt1 = 0, goneAt2 = 0;
    private static boolean renderGoneRestart = false;
    private static boolean suffixSet = false;
    // 啟動是否成功：開啟時 +1，順利運作 20 秒後歸零。數字沒歸零代表上次啟動途中就當掉
    private static final String BOOT_PREFS = "boot", BOOT_FAILS = "fails";
    private final Handler bootHandler = new Handler(Looper.getMainLooper());
    private final Runnable bootOk = () -> getSharedPreferences(BOOT_PREFS, MODE_PRIVATE).edit().putInt(BOOT_FAILS, 0).apply();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        SharedPreferences boot = getSharedPreferences(BOOT_PREFS, MODE_PRIVATE);
        int fails = boot.getInt(BOOT_FAILS, 0);
        // 旋轉螢幕等設定變更也會重建畫面，不算啟動；畫面元件當掉後的重建要算
        boolean configChange = savedInstanceState != null && !renderGoneRestart;
        // 註：被回收記憶體後的重建 savedInstanceState 也不是 null，會走 configChange、不計次
        renderGoneRestart = false;
        if (configChange) fails = 0;
        else boot.edit().putInt(BOOT_FAILS, fails + 1).commit();
        // 覆蓋安裝新版後第一次開啟也先清一次暫存（舊版可能就是卡在壞掉的暫存）
        long installed = 0;
        try { installed = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime; } catch (Exception ignored) {}
        boolean justUpdated = installed != boot.getLong("installed", 0);
        if (justUpdated) boot.edit().putLong("installed", installed).apply();
        recordExitReasons(fails);
        // 連續 3 次以上開不起來：WebView 設定檔本身可能壞了，改用全新的設定檔，只把會議資料庫複製過去（原檔不動）
        if (fails >= 3 && boot.getString("profile", "").isEmpty() && Build.VERSION.SDK_INT >= 28) {
            if (rebuildProfile("r1")) {
                boot.edit().putString("profile", "r1").putInt(BOOT_FAILS, 1).commit();
                fails = 0;
            }
        }
        String profile = boot.getString("profile", "");
        if (!profile.isEmpty() && !suffixSet && Build.VERSION.SDK_INT >= 28) {
            try { WebView.setDataDirectorySuffix(profile); } catch (Exception e) { Log.e(TAG, "suffix", e); }
            suffixSet = true;
        }
        if (fails >= 1 || justUpdated) clearWebViewCaches(profile);
        // 同一版 WebView 曾經要靠軟體繪圖才開得起來，就一直用軟體繪圖，直到 WebView 更新
        String wvVer = webViewVersion();
        if (fails >= 2) boot.edit().putString("softFor", wvVer).apply();
        boolean soft = fails >= 2 || (!wvVer.isEmpty() && wvVer.equals(boot.getString("softFor", "")));

        registerPlugin(RecordingServicePlugin.class);
        registerPlugin(LocalAsrPlugin.class);
        registerPlugin(DriveAuthPlugin.class);
        registerPlugin(StorePlugin.class);
        super.onCreate(savedInstanceState);
        bootHandler.postDelayed(bootOk, 20_000);
        if (soft && bridge != null && bridge.getWebView() != null) {
            // 連續啟動失敗：改用軟體繪圖，避開顯示晶片驅動與 WebView 版本不合造成的當機
            Log.w(TAG, "safe mode: software rendering, fails=" + fails + ", webview=" + wvVer);
            bridge.getWebView().setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bridge != null && bridge.getWebView() != null) {
            // 切到其他 App 時，畫面程序維持和 App 一樣的重要性（錄音中有前景服務），不被系統優先回收
            bridge.getWebView().setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
        if (fails >= 1) Toast.makeText(this, "上次開啟失敗，已清除畫面暫存並以安全模式開啟（會議資料不受影響）", Toast.LENGTH_LONG).show();
        else if (!profile.isEmpty() && boot.getInt(BOOT_FAILS, 0) == 1 && !configChange)
            Toast.makeText(this, "已重建畫面設定並複製會議資料（原資料另有備份）", Toast.LENGTH_LONG).show();
        if (bridge != null) bridge.addWebViewListener(new WebViewListener() {
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                return handleRenderGone(view, detail);
            }
        });
    }

    private String webViewVersion() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.content.pm.PackageInfo p = WebView.getCurrentWebViewPackage();
                if (p != null) return p.packageName + "/" + p.versionName;
            }
        } catch (Exception ignored) {}
        return "";
    }

    @Override
    public void onDestroy() {
        bootHandler.removeCallbacks(bootOk);
        super.onDestroy();
    }

    /**
     * 只刪 WebView 可以重建的暫存：網頁快取、JS 編譯快取、繪圖（GPU／著色器）快取、實驗設定檔。
     * 會議資料所在的 IndexedDB、Local Storage、databases 等一律不碰（用白名單，不用排除法）。
     */
    private void clearWebViewCaches(String profile) {
        File data = new File(getApplicationInfo().dataDir);
        File wv = new File(data, profile.isEmpty() ? "app_webview" : "app_webview_" + profile);
        File[] targets = {
                new File(getCacheDir(), "WebView"),           // HTTP Cache、Code Cache
                new File(wv, "GPUCache"),
                new File(wv, "Default/GPUCache"),
                new File(wv, "GrShaderCache"),
                new File(wv, "GraphiteDawnCache"),
                new File(wv, "ShaderCache"),
                new File(wv, "Default/Code Cache"),
                new File(wv, "variations_seed"),
                new File(wv, "variations_seed_new"),
                new File(wv, "variations_stamp"),
        };
        for (File f : targets) {
            try {
                if (f.exists()) {
                    deleteTree(f);
                    Log.w(TAG, "cleared " + f.getName());
                }
            } catch (Exception e) {
                Log.e(TAG, "clear failed " + f, e);
            }
        }
    }

    /**
     * 建立新的 WebView 設定檔：只複製會議資料庫（IndexedDB）與 Local Storage（設定、金鑰）。
     * 先備份一份到 App 資料夾（files/store/webview-backup/），原本的設定檔完全不動，隨時可以回頭。
     */
    private boolean rebuildProfile(String suffix) {
        File data = new File(getApplicationInfo().dataDir);
        File oldDef = new File(data, "app_webview/Default");
        File neu = new File(data, "app_webview_" + suffix);
        String[] keep = {"IndexedDB", "Local Storage"};
        try {
            File bak = new File(StorePlugin.root(this), "webview-backup/" + System.currentTimeMillis());
            for (String k : keep) {
                File src = new File(oldDef, k);
                if (src.exists()) copyTree(src, new File(bak, k));
            }
            if (neu.exists()) deleteTree(neu);
            for (String k : keep) {
                File src = new File(oldDef, k);
                if (src.exists()) copyTree(src, new File(neu, "Default/" + k));
            }
            Log.w(TAG, "rebuilt WebView profile " + suffix);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "rebuild profile failed", e);
            return false;
        }
    }

    private static void copyTree(File src, File dst) throws java.io.IOException {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) throw new java.io.IOException("mkdir " + dst);
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(dst, k.getName()));
        } else {
            if (src.getName().equals("LOCK")) return; // LevelDB 的鎖檔不用複製
            try (java.io.FileInputStream in = new java.io.FileInputStream(src);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                out.getFD().sync();
            }
            if (dst.length() != src.length()) throw new java.io.IOException("copy size mismatch " + src);
        }
    }

    /**
     * 把系統記下的最近幾次結束原因寫成文字檔，同時放一份到「下載」資料夾（會議速記當機紀錄.txt），
     * App 打不開時也能把檔案傳給開發者看。只在上次啟動失敗時寫。
     */
    private void recordExitReasons(int fails) {
        if (fails < 1 || Build.VERSION.SDK_INT < 30) return;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            java.util.List<android.app.ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(getPackageName(), 0, 5);
            StringBuilder sb = new StringBuilder();
            sb.append("會議速記當機紀錄\n時間：").append(new java.util.Date()).append("\n")
              .append("Android ").append(Build.VERSION.RELEASE).append(" / ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
              .append("WebView：").append(webViewVersion()).append("\n連續開啟失敗次數：").append(fails).append("\n\n");
            for (android.app.ApplicationExitInfo e : list) {
                sb.append(new java.util.Date(e.getTimestamp())).append("  原因代碼 ").append(e.getReason())
                  .append("  狀態 ").append(e.getStatus()).append("\n  ").append(e.getDescription()).append("\n");
                if (Build.VERSION.SDK_INT >= 31 && e.getReason() == android.app.ApplicationExitInfo.REASON_CRASH) {
                    try (java.io.InputStream in = e.getTraceInputStream()) {
                        if (in != null) {
                            byte[] b = new byte[8192];
                            int n = in.read(b);
                            if (n > 0) sb.append(new String(b, 0, n, "UTF-8")).append("\n");
                        }
                    } catch (Exception ignored) {}
                }
            }
            String text = sb.toString();
            StorePlugin.writeAtomic(new File(StorePlugin.root(this), "crash-log.txt"), text);
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "會議速記當機紀錄.txt");
            cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            android.net.Uri uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri != null) try (java.io.OutputStream o = getContentResolver().openOutputStream(uri)) {
                o.write(text.getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.e(TAG, "exit reasons", e);
        }
    }

    private static void deleteTree(File f) throws java.io.IOException {
        // 不跟著捷徑（symlink）走出原本的資料夾
        File parent = f.getParentFile();
        boolean realDir = f.isDirectory() && parent != null
                && f.getCanonicalFile().equals(new File(parent.getCanonicalFile(), f.getName()));
        File[] kids = realDir ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /**
     * 預設情況下 WebView 的畫面程序當掉（或被系統回收記憶體）時，Android 會把整個 App 關掉。
     * 改成丟掉壞掉的 WebView、重建畫面；資料都在 IndexedDB，重建後會照常讀回來。
     * 一分鐘內連續當掉 3 次就不再重建，提示更新 Android System WebView，避免無限重開。
     */
    private boolean handleRenderGone(WebView view, RenderProcessGoneDetail detail) {
        boolean crashed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && detail != null && detail.didCrash();
        Log.e(TAG, "WebView render process gone, crashed=" + crashed);
        try {
            ViewGroup parent = (ViewGroup) view.getParent();
            if (parent != null) parent.removeView(view);
            view.destroy();
        } catch (Exception e) {
            Log.e(TAG, "destroy WebView failed", e);
        }
        long now = SystemClock.elapsedRealtime();
        boolean loop = goneAt2 > 0 && now - goneAt2 < 60_000;
        goneAt2 = goneAt1;
        goneAt1 = now;
        if (loop) {
            Toast.makeText(this, "畫面元件連續當掉。會議與錄音仍保存在手機裡，請到 Play 商店更新「Android System WebView」後再開啟", Toast.LENGTH_LONG).show();
            finish();
        } else {
            Toast.makeText(this, "畫面元件發生錯誤，正在重新載入（資料不受影響）", Toast.LENGTH_SHORT).show();
            // 真的當掉才算一次啟動失敗；在背景被系統回收記憶體不算，免得誤進安全模式
            renderGoneRestart = crashed;
            recreate();
        }
        return true; // 已處理，不讓系統關掉整個 App
    }
}
