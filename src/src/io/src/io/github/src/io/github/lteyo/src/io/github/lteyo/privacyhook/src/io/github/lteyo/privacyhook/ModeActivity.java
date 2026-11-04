package io.github.lteyo.privacyhook;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;

public class ModeActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(android.R.style.Theme_Material_Light);
        setContentView(R.layout.activity_mode);

        status = findViewById(R.id.status);
        findViewById(R.id.btn_strict).setOnClickListener(v -> setMode(Mode.STRICT));
        findViewById(R.id.btn_blacklist).setOnClickListener(v -> setMode(Mode.BLACKLIST));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        String current = ModeFile.read();
        status.setText("当前模式：" + current
                + "\n目标：com.miui.guardprovider、com.mobiletools.systemhelper");
        ((Button) findViewById(current.equals(Mode.BLACKLIST)
                ? R.id.btn_strict : R.id.btn_blacklist)).setText("切换到这里");
    }

    private void setMode(String mode) {
        boolean ok = ModeFile.write(this, mode);
        Toast.makeText(this, ok ? "已切换：" + mode : "写入失败，请在 KernelSU 授权后重试",
                Toast.LENGTH_LONG).show();
        refreshStatus();
    }

    private static final class Mode {
        static final String STRICT = "strict";
        static final String BLACKLIST = "blacklist";
    }

    /** Reads and writes the mode file; the module rereads it every 30 seconds. */
    private static final class ModeFile {
        static final String PATH = "/data/local/tmp/privacyhook.mode";

        static String read() {
            try {
                return new String(java.nio.file.Files.readAllBytes(new java.io.File(PATH).toPath()))
                        .trim().toLowerCase();
            } catch (Throwable t) {
                return Mode.STRICT;
            }
        }

        static boolean write(Activity activity, String mode) {
            try {
                Process p = Runtime.getRuntime().exec("su");
                DataOutputStream os = new DataOutputStream(p.getOutputStream());
                os.writeBytes("echo " + mode + " > " + PATH + "\n");
                os.writeBytes("chmod 644 " + PATH + "\n");
                os.writeBytes("exit\n");
                os.flush();
                return p.waitFor() == 0;
            } catch (Throwable t) {
                return false;
            }
        }
    }
}
