package io.github.lteyo.privacyhook;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.io.File;

public class ModeActivity extends Activity {

    private static final String MODE_FILE = "/data/local/tmp/privacyhook.mode";
    private static final String MODE_BLACKLIST = "blacklist";

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("Privacy Hook");
        title.setTextSize(18);
        root.addView(title);

        status = new TextView(this);
        status.setPadding(0, pad, 0, pad);
        status.setTextSize(15);
        root.addView(status);

        android.widget.Button strict = new android.widget.Button(this);
        strict.setText("strict — block all egress\nrecommended default");
        strict.setOnClickListener(v -> setMode("strict"));
        root.addView(strict);

        android.widget.Button blacklist = new android.widget.Button(this);
        blacklist.setText("blacklist — fraud/telemetry only\nallows virus engine & manager API");
        blacklist.setOnClickListener(v -> setMode(MODE_BLACKLIST));
        root.addView(blacklist);

        TextView hint = new TextView(this);
        hint.setTextSize(12);
        hint.setText("Takes effect within 30s, or run:\n"
                + "adb shell su -c 'killall com.miui.guardprovider'\n\n"
                + "Every URL is logged under tag PrivacyHook.");
        root.addView(hint);

        setContentView(root);
        refreshStatus();
    }

    private void refreshStatus() {
        String m = readMode();
        status.setText("mode: " + (MODE_BLACKLIST.equals(m) ? MODE_BLACKLIST : "strict")
                + "\nfile: " + MODE_FILE);
    }

    private String readMode() {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(new File(MODE_FILE).toPath());
            return new String(b).trim().toLowerCase();
        } catch (Throwable t) {
            return "strict";
        }
    }

    private void setMode(String mode) {
        boolean ok = false;
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes("echo " + mode + " > " + MODE_FILE + "\n");
            os.writeBytes("chmod 644 " + MODE_FILE + "\n");
            os.writeBytes("exit\n");
            os.flush();
            ok = p.waitFor() == 0;
        } catch (Throwable ignored) {
        }
        Toast.makeText(this, ok ? mode : "failed — grant su in KernelSU", Toast.LENGTH_LONG).show();
        refreshStatus();
    }
}
