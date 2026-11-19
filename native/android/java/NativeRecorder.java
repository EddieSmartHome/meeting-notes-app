package tw.eddiehsu.meetingnotes;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * 原生錄音：不經過 WebView，畫面被系統回收或重新載入時錄音照常進行。
 *
 * - 完整錄音：AudioRecord → AAC 編碼 → 邊錄邊寫成 ADTS 檔（audio/<會議>/rec.aac），每一小格聲音寫完就落地，
 *   當機最多只丟最後零點幾秒。結束錄音時轉成 m4a（audio/<會議>/0.m4a）給播放器與雲端同步用。
 * - 轉錄片段：同時把聲音降到 16 kHz，用和網頁版相同的音量規則切段（滿 10 秒且停頓 0.5 秒、最長 25 秒、沒有人聲不送），
 *   每段存成 WAV（chunks/<會議>/<起點>.wav）並寫一個說明檔，網頁端的轉錄佇列會自動接手；畫面不在時也照存。
 * - 接續錄音：已有 m4a 時先轉回 ADTS，新的聲音接在後面，時間軸從原本長度往下算。
 */
public class NativeRecorder implements Runnable {
    private static final String TAG = "MeetingNotes";

    public interface Listener {
        void onLevel(float rms);
        void onChunk(String mid, double start, double dur);
        void onStopped(String mid, double duration, String error);
    }

    static volatile NativeRecorder current;
    static volatile Listener listener;

    final String mid;
    final File root, audioDir, chunkDir, adts, m4a;
    private final int kbps;
    private final String mode;
    int sampleRate;
    double base;              // 接續錄音時，之前已有的長度（秒）
    private long samples;     // 這次錄進去的樣本數（不含暫停）
    volatile boolean paused;
    private volatile boolean stopping;
    private volatile String error;
    private volatile double finalDuration = -1;
    private Thread thread;
    private AudioRecord rec;
    private MediaCodec enc;
    private FileOutputStream out;
    private long encPts;
    private final Object lock = new Object();

    // 切段
    private float floor = 0.004f;
    private ShortBuf seg = new ShortBuf();
    private double segStart;
    private boolean segVoice;
    private int silentMs;
    private double rsPos;       // 降頻取樣位置
    private final Context app;

    NativeRecorder(Context ctx, String mid, int kbps, String mode) {
        this.app = ctx.getApplicationContext();
        this.mid = mid;
        this.kbps = kbps > 0 ? kbps : 64;
        this.mode = mode == null ? "meeting" : mode;
        root = StorePlugin.root(ctx);
        audioDir = new File(root, "audio/" + mid);
        chunkDir = new File(root, "chunks/" + mid);
        adts = new File(audioDir, "rec.aac");
        m4a = new File(audioDir, "0.m4a");
    }

    double elapsed() {
        return base + (sampleRate > 0 ? (double) samples / sampleRate : 0);
    }

    /** 準備好麥克風、編碼器與檔案；失敗時丟出訊息給網頁端顯示。在呼叫端的執行緒執行。 */
    void prepare() throws Exception {
        audioDir.mkdirs();
        chunkDir.mkdirs();
        int wantRate = 0;
        if (!adts.exists() && m4a.exists() && m4a.length() > 0) {
            // 接續錄音：把之前的 m4a 轉回可以往後接的 ADTS
            int[] info = m4aToAdts(m4a, adts);
            if (info == null) throw new Exception("這場會議的舊錄音格式無法接續錄音，請按「＋ 新會議」另開一場");
            wantRate = info[0];
        } else if (adts.exists() && adts.length() > 0) {
            int[] info = adtsInfo(adts);
            if (info != null) wantRate = info[0];
        }
        if (adts.exists() && adts.length() > 0) {
            int[] info = adtsInfo(adts);
            if (info != null && info[0] > 0) base = (double) info[2] * 1024 / info[0];
        }

        int[] rates = wantRate > 0 ? new int[]{wantRate} : new int[]{48000, 44100, 16000};
        for (int sr : rates) {
            int min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) continue;
            AudioRecord r = null;
            try {
                r = new AudioRecord(source(), sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 4, sr / 2 * 2));
            } catch (SecurityException e) {
                throw new Exception("沒有麥克風權限：請到手機「設定 → 應用程式 → 會議速記 → 權限」開啟麥克風");
            } catch (Exception ignored) {
            }
            if (r != null && r.getState() == AudioRecord.STATE_INITIALIZED) {
                rec = r;
                sampleRate = sr;
                break;
            }
            if (r != null) r.release();
        }
        if (rec == null) {
            if (wantRate > 0) throw new Exception("麥克風不支援舊錄音的取樣率 " + wantRate + " Hz，無法接續，請另開一場會議");
            throw new Exception("無法啟動麥克風，可能被其他 App 占用");
        }

        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();
        out = new FileOutputStream(adts, true);
        segStart = base;
    }

    private int source() {
        if ("call".equals(mode)) return MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        if ("raw".equals(mode)) {
            return Build.VERSION.SDK_INT >= 24 ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.VOICE_RECOGNITION;
        }
        return MediaRecorder.AudioSource.MIC;
    }

    void begin() {
        rec.startRecording();
        current = this;
        thread = new Thread(this, "native-recorder");
        thread.start();
    }

    void pause() {
        synchronized (lock) {
            if (paused) return;
            paused = true;
            try { rec.stop(); } catch (Exception ignored) {}
            cutSeg();
            writeState();
        }
    }

    void resume() {
        synchronized (lock) {
            if (!paused) return;
            try { rec.startRecording(); } catch (Exception e) { Log.e(TAG, "resume", e); }
            segStart = elapsed();
            silentMs = 0;
            paused = false;
            lock.notifyAll();
        }
    }

    /** 結束錄音並等寫檔完成，回傳總長度（秒）。 */
    double stop() {
        stopping = true;
        synchronized (lock) { lock.notifyAll(); }
        if (thread != null) {
            try { thread.join(90000); } catch (InterruptedException ignored) {}
        }
        return finalDuration >= 0 ? finalDuration : elapsed();
    }

    @Override
    public void run() {
        int block = sampleRate / 10; // 100 ms
        short[] buf = new short[block];
        long lastState = 0;
        try {
            while (!stopping) {
                synchronized (lock) {
                    while (paused && !stopping) {
                        try { lock.wait(); } catch (InterruptedException ignored) {}
                    }
                }
                if (stopping) break;
                int n = 0;
                while (n < block && !stopping && !paused) {
                    int r = rec.read(buf, n, block - n);
                    if (r < 0) {
                        if (paused || stopping) break; // 暫停時麥克風已停，讀取失敗是正常的
                        throw new IOException("麥克風讀取錯誤 " + r);
                    }
                    n += r;
                }
                if (n <= 0) continue;
                synchronized (lock) {
                    encode(buf, n, false);
                    samples += n;
                    float rms = rms(buf, n);
                    Listener l = listener;
                    if (l != null) l.onLevel(rms);
                    segment(buf, n, rms);
                }
                long now = System.currentTimeMillis();
                if (now - lastState > 5000) { writeState(); lastState = now; }
            }
        } catch (Exception e) {
            Log.e(TAG, "recording failed", e);
            error = e.getMessage();
        }
        finish();
    }

    private void finish() {
        try { rec.stop(); } catch (Exception ignored) {}
        try { rec.release(); } catch (Exception ignored) {}
        synchronized (lock) {
            cutSeg();
            try { encode(new short[0], 0, true); } catch (Exception e) { Log.e(TAG, "flush", e); }
        }
        try { enc.stop(); } catch (Exception ignored) {}
        try { enc.release(); } catch (Exception ignored) {}
        try { out.close(); } catch (Exception ignored) {}
        double dur = elapsed();
        try {
            double d = finalizeAudio(adts, m4a);
            if (d > 0) dur = d;
        } catch (Exception e) {
            Log.e(TAG, "remux failed", e);
            if (error == null) error = "錄音存檔失敗：" + e.getMessage();
        }
        finalDuration = dur;
        new File(root, "rec/state.json").delete();
        try {
            // 畫面不在時結束的錄音：下次開啟由 recRepair 告訴網頁端長度
            JSONObject o = new JSONObject();
            o.put("mid", mid);
            o.put("duration", dur);
            StorePlugin.writeAtomic(new File(root, "rec/last.json"), o.toString());
        } catch (Exception ignored) {}
        if (current == this) current = null;
        Listener l = listener;
        if (l != null) l.onStopped(mid, dur, error);
        // 錄音結束（包括出錯）就收掉前景服務與常駐通知
        try { app.stopService(new android.content.Intent(app, RecordingService.class)); } catch (Exception ignored) {}
    }

    private void writeState() {
        try {
            JSONObject o = new JSONObject();
            o.put("mid", mid);
            o.put("elapsed", elapsed());
            o.put("paused", paused);
            o.put("at", System.currentTimeMillis());
            StorePlugin.writeAtomic(new File(root, "rec/state.json"), o.toString());
        } catch (Exception ignored) {}
    }

    // ---------- AAC 編碼，輸出 ADTS ----------
    private void encode(short[] pcm, int n, boolean eos) throws IOException {
        int off = 0;
        while (off < n || eos) {
            int ii = enc.dequeueInputBuffer(10000);
            if (ii >= 0) {
                ByteBuffer ib = enc.getInputBuffer(ii);
                ib.clear();
                int cnt = Math.min(n - off, ib.remaining() / 2);
                ib.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, off, cnt);
                long pts = encPts * 1000000L / sampleRate;
                encPts += cnt;
                off += cnt;
                boolean last = eos && off >= n;
                enc.queueInputBuffer(ii, 0, cnt * 2, pts, last ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                if (last) { drain(true); return; }
            }
            drain(false);
        }
    }

    private void drain(boolean untilEos) throws IOException {
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        int waits = 0;
        for (;;) {
            int oi = enc.dequeueOutputBuffer(bi, untilEos ? 10000 : 0);
            if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (untilEos && ++waits < 300) continue; // 最多等 3 秒
                return;
            }
            if (oi < 0) continue;
            ByteBuffer ob = enc.getOutputBuffer(oi);
            if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bi.size > 0) {
                byte[] frame = new byte[bi.size + 7];
                adtsHeader(frame, bi.size + 7, sampleRate, 1);
                ob.position(bi.offset);
                ob.get(frame, 7, bi.size);
                out.write(frame);
            }
            enc.releaseOutputBuffer(oi, false);
            if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }

    static final int[] RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};

    static int rateIndex(int sr) {
        for (int i = 0; i < RATES.length; i++) if (RATES[i] == sr) return i;
        return 4;
    }

    static void adtsHeader(byte[] p, int len, int sr, int ch) {
        int profile = 2; // AAC LC
        int fi = rateIndex(sr);
        p[0] = (byte) 0xFF;
        p[1] = (byte) 0xF1;
        p[2] = (byte) (((profile - 1) << 6) + (fi << 2) + (ch >> 2));
        p[3] = (byte) (((ch & 3) << 6) + (len >> 11));
        p[4] = (byte) ((len & 0x7FF) >> 3);
        p[5] = (byte) (((len & 7) << 5) + 0x1F);
        p[6] = (byte) 0xFC;
    }

    /** 回傳 {取樣率, 聲道, 音框數}；檔案不是 ADTS 時回傳 null。 */
    static int[] adtsInfo(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] h = new byte[9];
            int sr = 0, ch = 0, frames = 0;
            while (readFully(in, h, 7)) {
                if ((h[0] & 0xFF) != 0xFF || (h[1] & 0xF0) != 0xF0) break;
                if (frames == 0) {
                    sr = RATES[Math.min((h[2] >> 2) & 0xF, RATES.length - 1)];
                    ch = ((h[2] & 1) << 2) | ((h[3] >> 6) & 3);
                }
                int len = ((h[3] & 3) << 11) | ((h[4] & 0xFF) << 3) | ((h[5] & 0xE0) >> 5);
                if (len < 7) break;
                if (skipFully(in, len - 7) < len - 7) break; // 最後一格沒寫完（當機）：略過
                frames++;
            }
            return frames > 0 ? new int[]{sr, ch, frames} : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean readFully(InputStream in, byte[] b, int n) throws IOException {
        int o = 0;
        while (o < n) {
            int r = in.read(b, o, n - o);
            if (r < 0) return false;
            o += r;
        }
        return true;
    }

    private static long skipFully(InputStream in, long n) throws IOException {
        long s = 0;
        byte[] tmp = new byte[4096];
        while (s < n) {
            int r = in.read(tmp, 0, (int) Math.min(tmp.length, n - s));
            if (r < 0) break;
            s += r;
        }
        return s;
    }

    /**
     * 把 ADTS 轉成 m4a（先寫暫存檔再改名，最後才刪 ADTS；中途當機下次開啟會重做）。回傳長度（秒）。
     */
    static double finalizeAudio(File adts, File m4a) throws IOException {
        if (!adts.exists()) return 0;
        int[] info = adtsInfo(adts);
        if (info == null) {
            adts.delete();
            return 0;
        }
        int sr = info[0], ch = Math.max(1, info[1]);
        File tmp = new File(m4a.getParentFile(), "0.m4a.tmp");
        tmp.delete();
        MediaMuxer mux = new MediaMuxer(tmp.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, ch);
        int asc = (2 << 11) | (rateIndex(sr) << 7) | (ch << 3);
        f.setByteBuffer("csd-0", ByteBuffer.wrap(new byte[]{(byte) (asc >> 8), (byte) asc}));
        int track = mux.addTrack(f);
        mux.start();
        int frames = 0;
        try (InputStream in = new java.io.BufferedInputStream(new FileInputStream(adts), 65536)) {
            byte[] h = new byte[9];
            byte[] data = new byte[8192];
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            while (readFully(in, h, 7)) {
                if ((h[0] & 0xFF) != 0xFF || (h[1] & 0xF0) != 0xF0) break;
                boolean crc = (h[1] & 1) == 0;
                int len = ((h[3] & 3) << 11) | ((h[4] & 0xFF) << 3) | ((h[5] & 0xE0) >> 5);
                int hl = crc ? 9 : 7;
                if (crc && !readFully(in, new byte[2], 2)) break;
                int size = len - hl;
                if (size <= 0) break;
                if (data.length < size) data = new byte[size];
                if (!readFully(in, data, size)) break;
                bi.set(0, size, (long) frames * 1024 * 1000000L / sr, MediaCodec.BUFFER_FLAG_KEY_FRAME);
                mux.writeSampleData(track, ByteBuffer.wrap(data, 0, size), bi);
                frames++;
            }
        }
        mux.stop();
        mux.release();
        if (frames == 0) {
            tmp.delete();
            adts.delete();
            return 0;
        }
        if (m4a.exists() && !m4a.delete()) throw new IOException("無法取代舊錄音");
        if (!tmp.renameTo(m4a)) throw new IOException("無法寫入錄音檔");
        adts.delete();
        return (double) frames * 1024 / sr;
    }

    /** m4a（AAC）轉回 ADTS，回傳 {取樣率, 聲道}；不是單聲道 AAC 或讀不到時回傳 null。 */
    static int[] m4aToAdts(File m4a, File adts) {
        MediaExtractor ex = new MediaExtractor();
        File tmp = new File(adts.getParentFile(), "rec.aac.tmp");
        try {
            ex.setDataSource(m4a.getPath());
            int track = -1;
            MediaFormat f = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat tf = ex.getTrackFormat(i);
                if (MediaFormat.MIMETYPE_AUDIO_AAC.equals(tf.getString(MediaFormat.KEY_MIME))) { track = i; f = tf; break; }
            }
            if (track < 0) return null;
            int sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE), ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            if (ch != 1) return null;
            ex.selectTrack(track);
            ByteBuffer bb = ByteBuffer.allocate(1 << 16);
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                for (;;) {
                    bb.clear();
                    int n = ex.readSampleData(bb, 0);
                    if (n < 0) break;
                    byte[] frame = new byte[n + 7];
                    adtsHeader(frame, n + 7, sr, ch);
                    bb.get(frame, 7, n);
                    o.write(frame);
                    ex.advance();
                }
            }
            if (!tmp.renameTo(adts)) return null;
            return new int[]{sr, ch};
        } catch (Exception e) {
            Log.e(TAG, "m4aToAdts", e);
            tmp.delete();
            return null;
        } finally {
            ex.release();
        }
    }

    // ---------- 切段（規則同網頁版 vadTick） ----------
    private static float rms(short[] b, int n) {
        double s = 0;
        for (int i = 0; i < n; i++) { double v = b[i] / 32768.0; s += v * v; }
        return n > 0 ? (float) Math.sqrt(s / n) : 0;
    }

    private void segment(short[] b, int n, float rms) {
        // 降到 16 kHz（線性內插）
        double step = sampleRate / 16000.0;
        double pos = rsPos;
        while (pos < n) {
            int i = (int) pos;
            double fr = pos - i;
            short a = b[i];
            short c = i + 1 < n ? b[i + 1] : b[i];
            seg.add((short) (a + (c - a) * fr));
            pos += step;
        }
        rsPos = pos - n;

        floor = rms < floor ? rms : floor * 0.998f + rms * 0.002f;
        boolean voice = rms > Math.max(0.008f, floor * 3);
        if (voice) { segVoice = true; silentMs = 0; } else silentMs += 100;
        double len = seg.size / 16000.0;
        if ((len >= 10 && silentMs >= 500) || len >= 25) cutSeg();
        else if (!segVoice && len >= 20) cutSeg();
    }

    private void cutSeg() {
        double dur = seg.size / 16000.0;
        double start = segStart;
        boolean keep = segVoice && dur > 0.8;
        ShortBuf s = seg;
        seg = new ShortBuf();
        segVoice = false;
        silentMs = 0;
        segStart = elapsed();
        if (!keep) return;
        try {
            String key = key(start);
            File wav = new File(chunkDir, key + ".wav");
            try (FileOutputStream o = new FileOutputStream(wav)) {
                o.write(wavHeader(s.size, 16000));
                ByteBuffer bb = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN);
                bb.asShortBuffer().put(s.data, 0, s.size);
                o.write(bb.array());
                o.getFD().sync();
            }
            JSONObject m = new JSONObject();
            m.put("mid", mid);
            m.put("start", Math.round(start * 1000) / 1000.0);
            m.put("dur", Math.round(dur * 1000) / 1000.0);
            m.put("tries", 0);
            m.put("file", key + ".wav");
            m.put("type", "audio/wav");
            StorePlugin.writeAtomic(new File(chunkDir, key + ".json"), m.toString());
            Listener l = listener;
            if (l != null) l.onChunk(mid, start, dur);
        } catch (Exception e) {
            Log.e(TAG, "chunk write failed", e);
        }
    }

    static String key(double start) {
        return String.format(Locale.US, "%.3f", start);
    }

    private static byte[] wavHeader(int n, int sr) {
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + n * 2).put("WAVE".getBytes());
        h.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(sr).putInt(sr * 2).putShort((short) 2).putShort((short) 16);
        h.put("data".getBytes()).putInt(n * 2);
        return h.array();
    }

    private static class ShortBuf {
        short[] data = new short[16000 * 26];
        int size;

        void add(short v) {
            if (size == data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
            data[size++] = v;
        }
    }

    /** 程式被系統結束時沒收尾的錄音：把 ADTS 轉成 m4a。回傳 {會議: 長度}。 */
    static JSONObject repairAll(Context ctx) {
        JSONObject res = new JSONObject();
        File[] dirs = new File(StorePlugin.root(ctx), "audio").listFiles();
        if (dirs == null) return res;
        NativeRecorder cur = current;
        for (File d : dirs) {
            if (cur != null && cur.mid.equals(d.getName())) continue;
            File a = new File(d, "rec.aac");
            if (!a.exists()) continue;
            try {
                double dur = finalizeAudio(a, new File(d, "0.m4a"));
                res.put(d.getName(), dur);
            } catch (Exception e) {
                Log.e(TAG, "repair " + d.getName(), e);
            }
        }
        new File(StorePlugin.root(ctx), "rec/state.json").delete();
        File last = new File(StorePlugin.root(ctx), "rec/last.json");
        try {
            if (last.exists()) {
                JSONObject o = new JSONObject(StorePlugin.readText(last));
                String m = o.optString("mid");
                if (!m.isEmpty() && !res.has(m) && (cur == null || !cur.mid.equals(m))) res.put(m, o.optDouble("duration", 0));
            }
        } catch (Exception ignored) {}
        last.delete();
        return res;
    }
}
