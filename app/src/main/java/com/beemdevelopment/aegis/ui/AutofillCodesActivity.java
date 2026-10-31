package com.beemdevelopment.aegis.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.autofill.AutofillManager;
import android.service.autofill.Dataset;
import android.view.WindowManager;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.otp.OtpInfoException;
import com.beemdevelopment.aegis.services.AegisAutofillService;
import com.beemdevelopment.aegis.vault.VaultEntry;
import com.beemdevelopment.aegis.vault.VaultFile;
import com.beemdevelopment.aegis.vault.VaultRepository;
import com.beemdevelopment.aegis.vault.VaultRepositoryException;

import java.util.Locale;

public class AutofillCodesActivity extends AegisActivity {
    private AutofillId field;
    private String sourcePackage;
    private String domain;
    private LinearLayout rows;
    private boolean authenticating;
    private final ActivityResultLauncher<Intent> auth = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                authenticating = false;
                if (result.getResultCode() == Activity.RESULT_OK) showEntries(); else finish();
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        field = getIntent().getParcelableExtra(AegisAutofillService.EXTRA_FIELD);
        sourcePackage = getIntent().getStringExtra(AegisAutofillService.EXTRA_PACKAGE);
        domain = getIntent().getStringExtra(AegisAutofillService.EXTRA_DOMAIN);
        if (field == null || sourcePackage == null) { finish(); return; }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        TextView destination = new TextView(this);
        destination.setText(getString(R.string.autofill_destination, sourcePackage,
                domain == null ? getString(R.string.autofill_no_website) : domain));
        root.addView(destination);
        ScrollView scroll = new ScrollView(this);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        root.addView(scroll);
        setContentView(root);
    }

    @Override protected void onStart() {
        super.onStart();
        if (field == null) return;
        if (!_vaultManager.isVaultLoaded()) {
            try {
                if (_vaultManager.isVaultInitNeeded()) { finish(); return; }
                VaultFile file = VaultRepository.readVaultFile(this);
                if (!file.isEncrypted()) _vaultManager.loadFrom(file);
                else if (!authenticating) {
                    authenticating = true;
                    auth.launch(new Intent(this, AuthActivity.class));
                }
            } catch (VaultRepositoryException e) { finish(); return; }
        }
        showEntries();
    }

    private void showEntries() {
        if (rows == null || !_vaultManager.isVaultLoaded()) return;
        rows.removeAllViews();
        for (VaultEntry entry : _vaultManager.getVault().getEntries()) {
            if (!matches(entry.getIssuer())) continue;
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setText(entry.getIssuer() + "  " + entry.getName());
            button.setOnClickListener(v -> returnCode(entry));
            rows.addView(button);
        }
        if (rows.getChildCount() == 0) {
            TextView none = new TextView(this);
            none.setText(R.string.autofill_no_entries);
            rows.addView(none);
        }
    }

    private boolean matches(String issuer) {
        if (issuer == null || issuer.trim().isEmpty()) return false;
        String label = issuer.trim().toLowerCase(Locale.ROOT);
        if (label.length() < 3) return false;
        return hasLabel(domain, label) || hasLabel(sourcePackage, label);
    }

    private static boolean hasLabel(String source, String label) {
        if (source == null) return false;
        for (String part : source.toLowerCase(Locale.ROOT).split("\\.")) {
            if (part.equals(label)) return true;
        }
        return false;
    }

    private void returnCode(VaultEntry entry) {
        try {
            String code = entry.getInfo().getOtp();
            RemoteViews presentation = new RemoteViews(getPackageName(), android.R.layout.simple_list_item_1);
            presentation.setTextViewText(android.R.id.text1, entry.getIssuer());
            Dataset dataset = new Dataset.Builder(presentation)
                    .setValue(field, AutofillValue.forText(code)).build();
            Intent result = new Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset);
            setResult(Activity.RESULT_OK, result);
            finish();
        } catch (OtpInfoException e) {
            Toast.makeText(this, R.string.floating_error, Toast.LENGTH_SHORT).show();
        }
    }
}
