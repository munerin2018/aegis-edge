package com.beemdevelopment.aegis.services;

import android.app.PendingIntent;
import android.app.assist.AssistStructure;
import android.content.Intent;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.FillCallback;
import android.service.autofill.Dataset;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveRequest;
import android.util.Pair;
import android.view.View;
import android.view.autofill.AutofillId;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.ui.AutofillCodesActivity;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public class AegisAutofillService extends AutofillService {
    public static final String EXTRA_FIELD = "aegis.autofill.field";
    public static final String EXTRA_PACKAGE = "aegis.autofill.package";
    public static final String EXTRA_DOMAIN = "aegis.autofill.domain";
    private static final AtomicInteger requests = new AtomicInteger();

    @Override public void onFillRequest(@NonNull FillRequest request, @NonNull CancellationSignal cancellation,
                                         @NonNull FillCallback callback) {
        if (request.getFillContexts().isEmpty()) { callback.onSuccess(null); return; }
        AssistStructure structure = request.getFillContexts().get(request.getFillContexts().size() - 1).getStructure();
        if (structure.getActivityComponent() == null) { callback.onSuccess(null); return; }
        String packageName = structure.getActivityComponent().getPackageName();
        if (packageName.equals(getPackageName())) { callback.onSuccess(null); return; }
        Match found = null;
        for (int i = 0; i < structure.getWindowNodeCount() && found == null; i++) {
            found = findFocusedOtp(structure.getWindowNodeAt(i).getRootViewNode(), null);
        }
        if (found == null || cancellation.isCanceled()) { callback.onSuccess(null); return; }
        Intent intent = new Intent(this, AutofillCodesActivity.class)
                .putExtra(EXTRA_FIELD, found.id).putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_DOMAIN, found.domain);
        PendingIntent pending = PendingIntent.getActivity(this, requests.incrementAndGet(), intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);
        RemoteViews presentation = new RemoteViews(getPackageName(), android.R.layout.simple_list_item_1);
        presentation.setTextViewText(android.R.id.text1, getString(R.string.autofill_pick));
        callback.onSuccess(new FillResponse.Builder().addDataset(new Dataset.Builder()
                .setValue(found.id, null, presentation).setAuthentication(pending.getIntentSender()).build()).build());
    }

    @Override public void onSaveRequest(@NonNull SaveRequest request, @NonNull SaveCallback callback) {
        callback.onSuccess();
    }

    private static Match findFocusedOtp(AssistStructure.ViewNode node, String domain) {
        if (node == null) return null;
        if (node.getWebDomain() != null) domain = node.getWebDomain();
        if (node.isFocused() && node.getAutofillType() == View.AUTOFILL_TYPE_TEXT
                && node.getAutofillId() != null && isOtp(node)) return new Match(node.getAutofillId(), domain);
        for (int i = 0; i < node.getChildCount(); i++) {
            Match match = findFocusedOtp(node.getChildAt(i), domain);
            if (match != null) return match;
        }
        return null;
    }

    private static boolean isOtp(AssistStructure.ViewNode node) {
        String[] hints = node.getAutofillHints();
        if (hints != null) for (String hint : hints) if (isOtpHint(hint)) return true;
        android.view.ViewStructure.HtmlInfo html = node.getHtmlInfo();
        if (html != null) for (Pair<String, String> attr : html.getAttributes()) {
            if ("autocomplete".equalsIgnoreCase(attr.first) && isOtpHint(attr.second)) return true;
        }
        return false;
    }

    private static boolean isOtpHint(String value) {
        if (value == null) return false;
        for (String part : value.toLowerCase(Locale.ROOT).split("\\s+")) {
            String normalized = part.replace("-", "").replace("_", "");
            if (normalized.equals("onetimecode") || normalized.equals("smsotpcode")) return true;
        }
        return false;
    }

    private static class Match {
        final AutofillId id;
        final String domain;
        Match(AutofillId id, String domain) { this.id = id; this.domain = domain; }
    }
}
