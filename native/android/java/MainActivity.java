package tw.eddiehsu.meetingnotes;

import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;
import android.widget.Toast;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

/** 由編譯流程覆蓋 `npx cap add android` 產生的 MainActivity，註冊 App 自己的原生外掛。 */
public class MainActivity extends BridgeActivity {
    private static final String TAG = "MeetingNotes";
    // 畫面元件（Android System WebView）當掉的時間，跨 Activity 重建保留
    private static long goneAt1 = 0, goneAt2 = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(RecordingServicePlugin.class);
        registerPlugin(LocalAsrPlugin.class);
        registerPlugin(DriveAuthPlugin.class);
        super.onCreate(savedInstanceState);
        if (bridge != null) bridge.addWebViewListener(new WebViewListener() {
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                return handleRenderGone(view, detail);
            }
        });
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
            recreate();
        }
        return true; // 已處理，不讓系統關掉整個 App
    }
}
