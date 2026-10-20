package tw.eddiehsu.meetingnotes;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

/** 由編譯流程覆蓋 `npx cap add android` 產生的 MainActivity，註冊 App 自己的原生外掛。 */
public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(RecordingServicePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
