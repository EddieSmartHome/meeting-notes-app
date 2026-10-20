package tw.eddiehsu.meetingnotes;

import android.os.StatFs;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本機語音辨識（離線）：sherpa-onnx ＋ Whisper Small（int8）。
 * 模型不放進 APK，第一次使用時從本 repo 的 Release 下載到 App 私有目錄（約 375 MB）。
 * 網頁端把錄音片段解碼成 16 kHz 單聲道 16-bit PCM 後傳進來。
 */
@CapacitorPlugin(name = "LocalAsr")
public class LocalAsrPlugin extends Plugin {
    // 模型放在本 repo 的 Release「local-asr-v1」（網頁端傳入網址）；沒傳時用這個預設值
    static final String DEFAULT_BASE = "https://github.com/hsueddiese/meeting-notes-app/releases/download/local-asr-v1/";
    static final String[] FILES = {"small-tokens.txt", "small-encoder.int8.onnx", "small-decoder.int8.onnx"};
    static final long NEED_BYTES = 420L * 1024 * 1024;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();   // 辨識
    private final ExecutorService fetcher = Executors.newSingleThreadExecutor();  // 下載
    private OfflineRecognizer recognizer;
    private volatile boolean downloading = false;

    private File dir() { return new File(getContext().getFilesDir(), "whisper-small"); }

    private boolean ready() {
        for (String f : FILES) if (!new File(dir(), f).isFile()) return false;
        return true;
    }

    private long sizeBytes() {
        long n = 0;
        File[] fs = dir().listFiles();
        if (fs != null) for (File f : fs) n += f.length();
        return n;
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        r.put("ready", ready());
        r.put("downloading", downloading);
        r.put("loaded", recognizer != null);
        r.put("bytes", sizeBytes());
        call.resolve(r);
    }

    @PluginMethod
    public void download(PluginCall call) {
        if (downloading) { call.reject("模型正在下載中"); return; }
        String b = call.getString("base");
        final String base = (b == null || b.isEmpty()) ? DEFAULT_BASE : (b.endsWith("/") ? b : b + "/");
        downloading = true;
        fetcher.execute(() -> {
            try {
                File d = dir();
                if (!d.isDirectory() && !d.mkdirs()) throw new Exception("無法建立模型資料夾");
                StatFs st = new StatFs(getContext().getFilesDir().getPath());
                if (st.getAvailableBytes() < NEED_BYTES - sizeBytes()) throw new Exception("手機剩餘空間不足（需要約 420 MB）");
                long[] totals = new long[FILES.length];
                long doneBefore = 0;
                for (int i = 0; i < FILES.length; i++) {
                    File out = new File(d, FILES[i]);
                    if (out.isFile() && out.length() > 0) { doneBefore += out.length(); continue; }
                    fetch(base + FILES[i], out, i, doneBefore);
                    doneBefore += out.length();
                }
                JSObject r = new JSObject();
                r.put("ready", ready());
                r.put("bytes", sizeBytes());
                call.resolve(r);
            } catch (Exception e) {
                call.reject("下載失敗：" + e.getMessage());
            } finally {
                downloading = false;
            }
        });
    }

    private void fetch(String url, File out, int index, long doneBefore) throws Exception {
        File tmp = new File(out.getPath() + ".part");
        HttpURLConnection c = null;
        for (int hop = 0; hop < 5; hop++) { // GitHub Release 會轉址到下載伺服器
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(30000);
            c.setReadTimeout(60000);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code != 200) { c.disconnect(); throw new Exception("HTTP " + code + "（" + out.getName() + "）"); }
            break;
        }
        if (c == null) throw new Exception("轉址次數過多");
        long total = c.getContentLengthLong();
        long got = 0, lastSent = 0;
        try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                fo.write(buf, 0, n);
                got += n;
                if (got - lastSent > (1 << 20)) {
                    lastSent = got;
                    JSObject p = new JSObject();
                    p.put("file", out.getName());
                    p.put("index", index);
                    p.put("count", FILES.length);
                    p.put("received", got);
                    p.put("total", total);
                    p.put("doneBefore", doneBefore);
                    notifyListeners("progress", p);
                }
            }
        } finally {
            c.disconnect();
        }
        if (total > 0 && got != total) { tmp.delete(); throw new Exception("下載不完整（" + out.getName() + "）"); }
        if (!tmp.renameTo(out)) throw new Exception("無法儲存 " + out.getName());
    }

    @PluginMethod
    public void remove(PluginCall call) {
        worker.execute(() -> {
            if (recognizer != null) { recognizer.release(); recognizer = null; }
            File[] fs = dir().listFiles();
            if (fs != null) for (File f : fs) f.delete();
            call.resolve();
        });
    }

    private OfflineRecognizer loadRecognizer() {
        if (recognizer != null) return recognizer;
        File d = dir();
        OfflineWhisperModelConfig w = new OfflineWhisperModelConfig();
        w.setEncoder(new File(d, "small-encoder.int8.onnx").getPath());
        w.setDecoder(new File(d, "small-decoder.int8.onnx").getPath());
        w.setLanguage("zh");          // 台灣會議以中文為主；英文單字 Whisper 仍會照原樣寫出
        w.setTask("transcribe");
        OfflineModelConfig m = new OfflineModelConfig();
        m.setWhisper(w);
        m.setTokens(new File(d, "small-tokens.txt").getPath());
        m.setNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)));
        m.setDebug(false);
        OfflineRecognizerConfig cfg = new OfflineRecognizerConfig();
        cfg.setModelConfig(m);
        recognizer = new OfflineRecognizer(null, cfg);
        return recognizer;
    }

    @PluginMethod
    public void transcribe(PluginCall call) {
        String pcm = call.getString("pcm");
        int sr = call.getInt("sampleRate", 16000);
        if (pcm == null) { call.reject("沒有音訊資料"); return; }
        worker.execute(() -> {
            try {
                if (!ready()) throw new Exception("尚未下載本機辨識模型");
                byte[] raw = Base64.decode(pcm, Base64.DEFAULT);
                ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                float[] samples = new float[raw.length / 2];
                for (int i = 0; i < samples.length; i++) samples[i] = bb.getShort() / 32768f;
                long t0 = System.currentTimeMillis();
                OfflineRecognizer rec = loadRecognizer();
                OfflineStream s = rec.createStream();
                s.acceptWaveform(samples, sr);
                rec.decode(s);
                OfflineRecognizerResult res = rec.getResult(s);
                s.release();
                JSObject r = new JSObject();
                r.put("text", res.getText());
                r.put("ms", System.currentTimeMillis() - t0);
                call.resolve(r);
            } catch (Throwable e) {
                call.reject("本機辨識失敗：" + e.getMessage());
            }
        });
    }
}
