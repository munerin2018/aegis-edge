package com.beemdevelopment.aegis.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.otp.OtpInfoException;
import com.beemdevelopment.aegis.vault.VaultEntry;
import com.beemdevelopment.aegis.vault.VaultFile;
import com.beemdevelopment.aegis.vault.VaultRepository;
import com.beemdevelopment.aegis.vault.VaultRepositoryException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class FloatingCodesActivity extends AegisActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<UUID, Long> revealed = new HashMap<>();
    private LinearLayout entries;
    private boolean authenticating;
    private boolean active;
    private final ActivityResultLauncher<Intent> auth = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                authenticating = false;
                if (result.getResultCode() != Activity.RESULT_OK) finish();
                else refresh();
            });
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!active) return;
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onSetTheme() { setTheme(R.style.Theme_Aegis_FloatingCodes); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setGravity(Gravity.END | Gravity.TOP);
        WindowManager.LayoutParams window = getWindow().getAttributes();
        window.y = (int) (120 * getResources().getDisplayMetrics().density);
        getWindow().setAttributes(window);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setText(R.string.floating_title);
        title.setTextSize(20);
        root.addView(title);
        ScrollView scroll = new ScrollView(this);
        entries = new LinearLayout(this);
        entries.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(entries);
        root.addView(scroll);
        setContentView(root);
        getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.85),
                WindowManager.LayoutParams.WRAP_CONTENT);
    }

    @Override protected void onStart() {
        super.onStart();
        active = true;
        if (!_vaultManager.isVaultLoaded()) {
            try {
                if (_vaultManager.isVaultInitNeeded()) { finish(); return; }
                VaultFile file = VaultRepository.readVaultFile(this);
                if (!file.isEncrypted()) _vaultManager.loadFrom(file);
                else if (!authenticating) {
                    authenticating = true;
                    auth.launch(new Intent(this, AuthActivity.class));
                }
            } catch (VaultRepositoryException e) {
                Toast.makeText(this, R.string.floating_error, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }
        handler.post(ticker);
    }

    @Override protected void onStop() {
        active = false;
        handler.removeCallbacks(ticker);
        entries.removeAllViews();
        revealed.clear();
        super.onStop();
    }

    private void refresh() {
        if (!active || !_vaultManager.isVaultLoaded()) return;
        entries.removeAllViews();
        if (_vaultManager.getVault().getEntries().isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.floating_empty);
            entries.addView(empty);
            return;
        }
        for (VaultEntry entry : _vaultManager.getVault().getEntries()) {
            Button button = new Button(this);
            boolean hidden = _prefs.isTapToRevealEnabled()
                    && revealed.getOrDefault(entry.getUUID(), 0L) <= SystemClock.elapsedRealtime();
            String code;
            try { code = hidden ? "••••••" : entry.getInfo().getOtp(); }
            catch (OtpInfoException | RuntimeException e) { code = "—"; }
            button.setText(entry.getIssuer() + "  " + entry.getName() + "\n" + code);
            button.setAllCaps(false);
            button.setOnClickListener(v -> {
                if (_prefs.isTapToRevealEnabled()
                        && revealed.getOrDefault(entry.getUUID(), 0L) <= SystemClock.elapsedRealtime()) {
                    revealed.put(entry.getUUID(), SystemClock.elapsedRealtime() + _prefs.getTapToRevealTime() * 1000L);
                    refresh();
                    return;
                }
                try {
                    String otp = entry.getInfo().getOtp();
                    ClipData clip = ClipData.newPlainText("text/plain", otp);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        PersistableBundle extras = new PersistableBundle();
                        extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
                        clip.getDescription().setExtras(extras);
                    }
                    ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(clip);
                    Toast.makeText(this, R.string.floating_copied, Toast.LENGTH_SHORT).show();
                    finish();
                } catch (OtpInfoException e) {
                    Toast.makeText(this, R.string.floating_error, Toast.LENGTH_SHORT).show();
                }
            });
            entries.addView(button);
        }
    }
}
