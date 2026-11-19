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
        if (fails >= 1 || justUpdated) clearWebViewCaches();
        // 同一版 WebView 曾經要靠軟體繪圖才開得起來，就一直用軟體繪圖，直到 WebView 更新
        String wvVer = webViewVersion();
        if (fails >= 2) boot.edit().putString("softFor", wvVer).apply();
        boolean soft = fails >= 2 || (!wvVer.isEmpty() && wvVer.equals(boot.getString("softFor", "")));

        registerPlugin(RecordingServicePlugin.class);
        registerPlugin(LocalAsrPlugin.class);
        registerPlugin(DriveAuthPlugin.class);
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
    private void clearWebViewCaches() {
        File data = new File(getApplicationInfo().dataDir);
        File wv = new File(data, "app_webview");
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
