package tw.eddiehsu.meetingnotes;

import android.content.Context;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * App 自己的資料夾（files/store/）：會議、待轉錄片段、錄音都放這裡，不放在 WebView 的 IndexedDB。
 * WebView 當掉、更新或退回舊版都不會影響這些檔案。
 *
 * 路徑一律是 store/ 底下的相對路徑，不能用 .. 跳出去。文字檔先寫暫存再改名，寫到一半當機也不會留下壞檔。
 */
@CapacitorPlugin(name = "Store")
public class StorePlugin extends Plugin {

    static File root(Context ctx) {
        File r = new File(ctx.getFilesDir(), "store");
        r.mkdirs();
        return r;
    }

    private File file(PluginCall call, String key) throws IOException {
        String p = call.getString(key, "");
        File r = root(getContext());
        File f = p.isEmpty() ? r : new File(r, p);
        String rc = r.getCanonicalPath(), fc = f.getCanonicalPath();
        if (!fc.equals(rc) && !fc.startsWith(rc + File.separator)) throw new IOException("路徑不在資料夾內：" + p);
        return f;
    }

    static void writeAtomic(File f, String text) throws IOException {
        writeAtomic(f, text.getBytes(StandardCharsets.UTF_8));
    }

    static void writeAtomic(File f, byte[] data) throws IOException {
        File dir = f.getParentFile();
        if (dir != null) dir.mkdirs();
        File tmp = new File(dir, f.getName() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(data);
            o.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("無法寫入 " + f.getName());
        }
    }

    static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream((int) Math.max(16, f.length()));
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    private static boolean deleteTree(File f) {
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteTree(k);
        return f.delete() || !f.exists();
    }

    private String rel(File f) throws IOException {
        String rc = root(getContext()).getCanonicalPath();
        return f.getCanonicalPath().substring(rc.length() + 1);
    }

    @PluginMethod
    public void info(PluginCall call) {
        File r = root(getContext());
        JSObject o = new JSObject();
        o.put("root", r.getAbsolutePath());
        o.put("free", r.getUsableSpace());
        call.resolve(o);
    }

    @PluginMethod
    public void writeText(PluginCall call) {
        try {
            writeAtomic(file(call, "path"), call.getString("data", ""));
            call.resolve();
        } catch (Exception e) {
            call.reject("寫入失敗：" + e.getMessage());
        }
    }

    @PluginMethod
    public void readText(PluginCall call) {
        try {
            File f = file(call, "path");
            JSObject o = new JSObject();
            if (f.isFile()) o.put("data", readText(f));
            call.resolve(o);
        } catch (Exception e) {
            call.reject("讀取失敗：" + e.getMessage());
        }
    }

    /** 一次讀回資料夾內（含下一層）所有 .json 檔：{items:[{path, data}]}。壞掉的檔案略過。 */
    @PluginMethod
    public void readJsonDir(PluginCall call) {
        try {
            File d = file(call, "dir");
            JSArray items = new JSArray();
            collect(d, call.getInt("depth", 1), items);
            JSObject o = new JSObject();
            o.put("items", items);
            call.resolve(o);
        } catch (Exception e) {
            call.reject("讀取失敗：" + e.getMessage());
        }
    }

    private void collect(File d, int depth, JSArray items) throws IOException {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                if (depth > 1) collect(f, depth - 1, items);
            } else if (f.getName().endsWith(".json")) {
                try {
                    JSObject it = new JSObject();
                    it.put("path", rel(f));
                    it.put("data", readText(f));
                    items.put(it);
                } catch (Exception ignored) {
                }
            }
        }
    }

    @PluginMethod
    public void list(PluginCall call) {
        try {
            File d = file(call, "dir");
            JSArray items = new JSArray();
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) {
                JSObject it = new JSObject();
                it.put("name", f.getName());
                it.put("dir", f.isDirectory());
                it.put("size", f.length());
                it.put("mtime", f.lastModified());
                items.put(it);
            }
            JSObject o = new JSObject();
            o.put("items", items);
            call.resolve(o);
        } catch (Exception e) {
            call.reject("讀取失敗：" + e.getMessage());
        }
    }

    /** 刪除檔案或整個資料夾；有 prefix 時刪除 dir 底下以 prefix 開頭的檔案。 */
    @PluginMethod
    public void remove(PluginCall call) {
        try {
            String prefix = call.getString("prefix");
            if (prefix != null) {
                File d = file(call, "dir");
                File[] fs = d.listFiles();
                if (fs != null) for (File f : fs) if (f.getName().startsWith(prefix)) deleteTree(f);
            } else {
                File f = file(call, "path");
                if (f.equals(root(getContext()))) throw new IOException("不能刪除整個資料夾");
                deleteTree(f);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("刪除失敗：" + e.getMessage());
        }
    }

    /** 寫二進位資料（base64）：first=true 時從頭寫，否則接在後面。大檔由網頁端分段呼叫。 */
    @PluginMethod
    public void writeB64(PluginCall call) {
        try {
            File f = file(call, "path");
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            byte[] data = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
            try (FileOutputStream o = new FileOutputStream(f, !call.getBoolean("first", false))) {
                o.write(data);
                if (call.getBoolean("sync", false)) o.getFD().sync();
            }
            JSObject r = new JSObject();
            r.put("size", f.length());
            call.resolve(r);
        } catch (Exception e) {
            call.reject("寫入失敗：" + e.getMessage());
        }
    }

    @PluginMethod
    public void rename(PluginCall call) {
        try {
            File a = file(call, "from"), b = file(call, "to");
            if (b.getParentFile() != null) b.getParentFile().mkdirs();
            if (b.exists() && !deleteTree(b)) throw new IOException("無法取代 " + b.getName());
            if (!a.renameTo(b)) throw new IOException("無法改名 " + a.getName());
            call.resolve();
        } catch (Exception e) {
            call.reject("改名失敗：" + e.getMessage());
        }
    }
}
