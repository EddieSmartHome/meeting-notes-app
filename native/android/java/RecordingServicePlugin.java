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
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * 網頁端以 Capacitor.Plugins.RecordingService 控制錄音。
 * start()/stop()：舊的網頁錄音模式只開關前景服務。
 * startRec()/pauseRec()/resumeRec()/stopRec()/recState()/recRepair()：原生錄音（v2.6.0 起），
 * 事件 recLevel（音量）、recChunk（存好一段待轉錄）、recStopped（錄音結束或出錯）。
 */
@CapacitorPlugin(name = "RecordingService", permissions = {
        @Permission(alias = "mic", strings = {Manifest.permission.RECORD_AUDIO})
})
public class RecordingServicePlugin extends Plugin {
    private long lastLevel;

    @Override
    public void load() {
        // 畫面重建時外掛也會重建：每次都把事件接到最新的這個外掛
        NativeRecorder.listener = new NativeRecorder.Listener() {
            @Override
            public void onLevel(float rms) {
                long now = System.currentTimeMillis();
                if (now - lastLevel < 200) return;
                lastLevel = now;
                JSObject o = new JSObject();
                o.put("rms", rms);
                notifyListeners("recLevel", o);
            }

            @Override
            public void onChunk(String mid, double start, double dur) {
                JSObject o = new JSObject();
                o.put("mid", mid);
                o.put("start", start);
                o.put("dur", dur);
                notifyListeners("recChunk", o);
            }

            @Override
            public void onStopped(String mid, double duration, String error) {
                JSObject o = new JSObject();
                o.put("mid", mid);
                o.put("duration", duration);
                if (error != null) o.put("error", error);
                notifyListeners("recStopped", o, true);
            }
        };
    }

    @PluginMethod
    public void startRec(PluginCall call) {
        if (getPermissionState("mic") != PermissionState.GRANTED) {
            requestPermissionForAlias("mic", call, "micResult");
            return;
        }
        doStartRec(call);
    }

    @PermissionCallback
    private void micResult(PluginCall call) {
        if (getPermissionState("mic") == PermissionState.GRANTED) doStartRec(call);
        else call.reject("沒有麥克風權限：請到手機「設定 → 應用程式 → 會議速記 → 權限」開啟麥克風");
    }

    private void doStartRec(PluginCall call) {
        String mid = call.getString("mid");
        if (mid == null || mid.isEmpty() || !mid.matches("[A-Za-z0-9_-]+")) {
            call.reject("會議編號不正確");
            return;
        }
        NativeRecorder cur = NativeRecorder.current;
        if (cur != null) {
            call.reject("另一場會議正在錄音");
            return;
        }
        NativeRecorder r = new NativeRecorder(getContext(), mid, call.getInt("kbps", 64), call.getString("mode", "meeting"));
        try {
            r.prepare();
        } catch (Exception e) {
            call.reject(e.getMessage() != null ? e.getMessage() : "無法開始錄音");
            return;
        }
        startService(call.getString("text", "錄音中，螢幕關閉或切到其他 App 也會持續錄音"));
        r.begin();
        JSObject o = new JSObject();
        o.put("base", r.base);
        o.put("sampleRate", r.sampleRate);
        call.resolve(o);
    }

    @PluginMethod
    public void pauseRec(PluginCall call) {
        NativeRecorder r = NativeRecorder.current;
        if (r != null) r.pause();
        call.resolve(state());
    }

    @PluginMethod
    public void resumeRec(PluginCall call) {
        NativeRecorder r = NativeRecorder.current;
        if (r != null) r.resume();
        call.resolve(state());
    }

    @PluginMethod
    public void stopRec(PluginCall call) {
        NativeRecorder r = NativeRecorder.current;
        JSObject o = new JSObject();
        if (r != null) {
            o.put("mid", r.mid);
            o.put("duration", r.stop());
        }
        call.resolve(o);
    }

    @PluginMethod
    public void recState(PluginCall call) {
        call.resolve(state());
    }

    private JSObject state() {
        NativeRecorder r = NativeRecorder.current;
        JSObject o = new JSObject();
        o.put("active", r != null);
        if (r != null) {
            o.put("mid", r.mid);
            o.put("elapsed", r.elapsed());
            o.put("paused", r.paused);
            o.put("sampleRate", r.sampleRate);
        }
        return o;
    }

    /** 上次程式被結束時沒收尾的錄音，轉成可播放的檔案。回傳 {items:{會議: 長度}}。 */
    @PluginMethod
    public void recRepair(PluginCall call) {
        JSObject o = new JSObject();
        try {
            o.put("items", JSObject.fromJSONObject(NativeRecorder.repairAll(getContext())));
        } catch (Exception e) {
            o.put("items", new JSObject());
        }
        call.resolve(o);
    }

    private void startService(String text) {
        Context ctx = getContext();
        if (Build.VERSION.SDK_INT >= 33 && getActivity() != null
                && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7301);
        }
        Intent i = new Intent(ctx, RecordingService.class);
        i.putExtra(RecordingService.EXTRA_TEXT, text);
        ContextCompat.startForegroundService(ctx, i);
    }

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
