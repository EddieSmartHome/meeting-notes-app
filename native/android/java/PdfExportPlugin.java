package tw.eddiehsu.meetingnotes;

import android.content.Context;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 會議記錄匯出 PDF（v2.6.3）：把網頁端排好的 HTML 放進一個看不見的 WebView，交給 Android 的列印功能。
 * 使用者在系統列印畫面選「儲存為 PDF」即可存檔，也可以直接送到印表機。
 * 文字由系統字型繪製，繁體中文正常顯示、PDF 內的文字可搜尋與複製；分頁由 WebView 的列印排版處理。
 */
@CapacitorPlugin(name = "PdfExport")
public class PdfExportPlugin extends Plugin {
    private static final String TAG = "MeetingNotes";
    // 列印進行中要一直留著這個 WebView，否則會被回收、列印畫面變成空白
    private WebView printView;

    @PluginMethod
    public void print(PluginCall call) {
        String html = call.getString("html", "");
        String name = call.getString("name", "會議記錄");
        if (html == null || html.isEmpty()) { call.reject("沒有內容"); return; }
        final String jobName = name == null || name.isEmpty() ? "會議記錄" : name;
        getActivity().runOnUiThread(() -> {
            try {
                if (printView != null) { try { printView.destroy(); } catch (Exception ignored) {} }
                WebView wv = new WebView(getActivity());
                wv.getSettings().setJavaScriptEnabled(false);
                wv.getSettings().setAllowFileAccess(false);
                wv.getSettings().setAllowContentAccess(false);
                final boolean[] done = {false};
                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        if (done[0]) return;
                        done[0] = true;
                        try {
                            PrintManager pm = (PrintManager) getActivity().getSystemService(Context.PRINT_SERVICE);
                            PrintDocumentAdapter adapter = view.createPrintDocumentAdapter(jobName);
                            PrintAttributes attrs = new PrintAttributes.Builder()
                                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                    .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                                    .build();
                            pm.print(jobName, adapter, attrs);
                            call.resolve(new JSObject().put("ok", true));
                        } catch (Exception e) {
                            Log.e(TAG, "pdf print", e);
                            call.reject("無法開啟列印畫面：" + e.getMessage());
                        }
                    }
                });
                printView = wv;
                wv.loadDataWithBaseURL("https://localhost/", html, "text/html", "UTF-8", null);
            } catch (Exception e) {
                Log.e(TAG, "pdf view", e);
                call.reject("無法建立 PDF：" + e.getMessage());
            }
        });
    }

    @Override
    protected void handleOnDestroy() {
        if (printView != null) { try { printView.destroy(); } catch (Exception ignored) {} printView = null; }
        super.handleOnDestroy();
    }
}
