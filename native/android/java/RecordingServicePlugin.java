package tw.eddiehsu.meetingnotes;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** 網頁端以 Capacitor.Plugins.RecordingService.start()/stop() 控制錄音前景服務。 */
@CapacitorPlugin(name = "RecordingService")
public class RecordingServicePlugin extends Plugin {

    @PluginMethod
    public void start(PluginCall call) {
        Context ctx = getContext();
        // Android 13 以上要允許通知，通知列才看得到「錄音中」；沒允許也不影響收音
        if (Build.VERSION.SDK_INT >= 33 && getActivity() != null
                && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7301);
        }
        try {
            Intent i = new Intent(ctx, RecordingService.class);
            String text = call.getString("text");
            if (text != null) i.putExtra(RecordingService.EXTRA_TEXT, text);
            ContextCompat.startForegroundService(ctx, i);
        } catch (Exception e) {
            call.reject("無法啟動背景錄音：" + e.getMessage());
            return;
        }
        // 背景時降低 WebView 被系統回收的機會
        try {
            WebView wv = getBridge().getWebView();
            if (wv != null && Build.VERSION.SDK_INT >= 26) {
                wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
            }
        } catch (Exception ignored) {
        }
        JSObject r = new JSObject();
        r.put("started", true);
        call.resolve(r);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Context ctx = getContext();
        ctx.stopService(new Intent(ctx, RecordingService.class));
        call.resolve();
    }
}
