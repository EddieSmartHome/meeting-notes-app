package tw.eddiehsu.meetingnotes;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Scope;

import java.util.Collections;

/**
 * Google 雲端硬碟同步用的登入：向使用者取得「App 專用資料夾」（drive.appdata）的存取權杖。
 * 只要 Google Cloud 專案裡有本 App 套件名稱＋簽章 SHA-1 的 Android OAuth 用戶端，就不需要在程式裡放 client ID。
 * 權杖約一小時有效；網頁端過期時再呼叫 authorize({interactive:false}) 取得新的。
 */
@CapacitorPlugin(name = "DriveAuth")
public class DriveAuthPlugin extends Plugin {
    static final String SCOPE = "https://www.googleapis.com/auth/drive.appdata";
    private ActivityResultLauncher<IntentSenderRequest> launcher;
    private PluginCall pending;

    @Override
    public void load() {
        launcher = getActivity().registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                res -> {
                    PluginCall c = pending;
                    pending = null;
                    if (c == null) return;
                    try {
                        AuthorizationResult r = Identity.getAuthorizationClient(getContext())
                                .getAuthorizationResultFromIntent(res.getData());
                        resolveToken(c, r);
                    } catch (ApiException e) {
                        c.reject("Google 授權未完成：" + describe(e) + "（畫面結果 " + res.getResultCode() + (res.getData() == null ? "，沒有回傳資料" : "") + "）");
                    } catch (Exception e) {
                        c.reject("Google 授權未完成：" + e.getClass().getSimpleName() + "：" + e.getMessage() + "（畫面結果 " + res.getResultCode() + "）");
                    }
                });
    }

    private void resolveToken(PluginCall c, AuthorizationResult r) {
        String t = r.getAccessToken();
        if (t == null || t.isEmpty()) { c.reject("沒有取得存取權杖"); return; }
        JSObject o = new JSObject();
        o.put("accessToken", t);
        c.resolve(o);
    }

    /** 把 Google 的錯誤轉成看得懂的文字：代碼、代碼名稱、Google 附的說明 */
    static String describe(ApiException e) {
        int code = e.getStatusCode();
        String name = CommonStatusCodes.getStatusCodeString(code);
        String sm = e.getStatus() != null ? e.getStatus().getStatusMessage() : null;
        String m = e.getMessage();
        StringBuilder b = new StringBuilder("代碼 ").append(code);
        if (name != null) b.append(" ").append(name);
        if (sm != null && !sm.isEmpty()) b.append("，Google 說明：").append(sm);
        else if (m != null && !m.isEmpty()) b.append("，Google 說明：").append(m);
        return b.toString();
    }

    @PluginMethod
    public void authorize(PluginCall call) {
        boolean interactive = call.getBoolean("interactive", true);
        AuthorizationRequest req = AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(new Scope(SCOPE)))
                .build();
        Identity.getAuthorizationClient(getActivity())
                .authorize(req)
                .addOnSuccessListener(r -> {
                    if (r.hasResolution()) {
                        if (!interactive || r.getPendingIntent() == null) {
                            call.reject("NEED_CONSENT");
                            return;
                        }
                        if (pending != null) { call.reject("正在等待另一個授權"); return; }
                        pending = call;
                        try {
                            launcher.launch(new IntentSenderRequest.Builder(r.getPendingIntent().getIntentSender()).build());
                        } catch (Exception e) {
                            pending = null;
                            call.reject("無法開啟 Google 授權畫面：" + e.getMessage());
                        }
                    } else {
                        resolveToken(call, r);
                    }
                })
                .addOnFailureListener(e -> {
                    String msg = e instanceof ApiException ? describe((ApiException) e) : e.getClass().getSimpleName() + "：" + e.getMessage();
                    call.reject("Google 授權失敗：" + msg + "。請確認 Google Cloud 已建立本 App 的 Android OAuth 用戶端");
                });
    }
}
