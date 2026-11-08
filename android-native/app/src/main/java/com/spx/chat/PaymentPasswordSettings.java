package com.spx.chat;

import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import org.json.JSONObject;

/** Server-owned configuration; payment credentials are never persisted on the device. */
final class PaymentPasswordSettings {
    static void open(MainActivity activity) { new PaymentPasswordSettings(activity).show(); }
    private final MainActivity activity;
    private final String server, token;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinearLayout body;
    private final TextView status;
    private AlertDialog dialog;
    private AlertDialog codeNotice;
    private EditText password, confirmation, code;
    private Button sendCode, save;
    private boolean configured, loaded, busy;
    private long resendAt;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!alive() || sendCode == null) return;
            long seconds = Math.max(0, (resendAt - android.os.SystemClock.elapsedRealtime() + 999) / 1000);
            sendCode.setText(seconds > 0 ? seconds + " 秒后重发" : "发送邮箱验证码");
            sendCode.setEnabled(!busy && seconds == 0);
            if (seconds > 0) handler.postDelayed(this, 1000);
        }
    };
    private PaymentPasswordSettings(MainActivity activity) {
        this.activity = activity; server = ApiClient.baseUrl(activity); token = ApiClient.token(activity);
        body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * activity.getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding, padding, padding);
        status = new TextView(activity); status.setTextSize(14);
    }
    private boolean alive() { return dialog != null && dialog.isShowing() && !activity.isFinishing() && !activity.isDestroyed() && server.equals(ApiClient.baseUrl(activity)) && token.equals(ApiClient.token(activity)); }
    private EditText field(String hint, boolean secret) {
        EditText input = new EditText(activity); input.setHint(hint); input.setContentDescription(hint);
        input.setId(View.generateViewId());
        input.setSingleLine(true); input.setInputType(InputType.TYPE_CLASS_NUMBER | (secret ? InputType.TYPE_NUMBER_VARIATION_PASSWORD : 0));
        input.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789"));
        if (secret) input.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        input.setSaveEnabled(false); if (android.os.Build.VERSION.SDK_INT >= 26) input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        body.addView(input, new LinearLayout.LayoutParams(-1, -2)); return input;
    }
    /** AlertDialog checks for editors only when first shown; async content must clear its IME-blocking flags. */
    static void enableKeyboard(AlertDialog target) {
        if (target.getWindow() == null) return;
        target.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        target.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }
    static void focusNumericInput(AlertDialog target, EditText input) {
        enableKeyboard(target);
        input.setShowSoftInputOnFocus(true);
        input.requestFocus();
        input.post(() -> {
            if (!target.isShowing() || !input.isAttachedToWindow() || !input.isEnabled() || !input.hasFocus()) return;
            androidx.core.view.WindowInsetsControllerCompat controller = androidx.core.view.ViewCompat.getWindowInsetsController(input);
            if (controller != null) controller.show(androidx.core.view.WindowInsetsCompat.Type.ime());
        });
    }
    private void show() {
        ScrollView scroll = new ScrollView(activity); scroll.addView(body);
        body.addView(status); status.setText("正在读取支付密码状态…");
        dialog = new AlertDialog.Builder(activity).setTitle("支付密码").setView(scroll).setNegativeButton("关闭", null).create();
        dialog.setOnDismissListener(v -> { handler.removeCallbacksAndMessages(null); if (codeNotice != null) codeNotice.dismiss(); clearSecrets(); });
        dialog.show();
        load();
    }
    private void load() {
        loaded = false;
        ApiClient.get(activity, "payment.password.status", null, new ApiClient.Callback() {
            public void onSuccess(Object value) {
                if (!alive()) return;
                if (!(value instanceof JSONObject)) { onError("支付密码状态返回异常，请重试"); return; }
                configured = ((JSONObject) value).optBoolean("configured"); loaded = true; render();
            }
            public void onError(String message) {
                if (!alive()) return; status.setText(message); body.removeAllViews(); body.addView(status);
                Button retry = new Button(activity); retry.setText("重新读取"); body.addView(retry);
                retry.setOnClickListener(v -> { retry.setEnabled(false); status.setText("正在读取…"); load(); });
            }
        });
    }
    private void render() {
        body.removeAllViews(); body.addView(status);
        status.setText(configured ? "已设置独立支付密码。修改时需验证账号绑定邮箱。" : "首次设置无需邮箱验证。请输入独立的六位数字支付密码，用于发送红包。请勿与登录密码共用。");
        if (configured) {
            code = field("邮箱验证码（六位数字）", false);
            sendCode = new Button(activity); body.addView(sendCode); sendCode.setOnClickListener(v -> sendCode()); tick.run();
        }
        password = field("新支付密码（六位数字）", true); confirmation = field("再次输入支付密码", true);
        save = new Button(activity); save.setText(configured ? "验证并修改支付密码" : "设置支付密码"); body.addView(save);
        save.setOnClickListener(v -> save());
        password.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        password.setNextFocusForwardId(confirmation.getId());
        confirmation.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        if (code != null) {
            code.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
            code.setNextFocusForwardId(password.getId());
        }
        focusNumericInput(dialog, configured ? code : password);
    }
    private void controls(boolean value) {
        busy = value; if (save != null) { save.setEnabled(!value && loaded); save.setText(value ? "正在处理…" : configured ? "验证并修改支付密码" : "设置支付密码"); }
        if (password != null) password.setEnabled(!value); if (confirmation != null) confirmation.setEnabled(!value); if (code != null) code.setEnabled(!value);
        handler.removeCallbacks(tick); tick.run();
    }
    private void sendCode() {
        if (busy || !loaded || android.os.SystemClock.elapsedRealtime() < resendAt) return;
        controls(true);
        ApiClient.post(activity, "payment.password.email.code", new JSONObject(), new ApiClient.Callback() {
            public void onSuccess(Object value) { if (!alive()) return; resendAt = android.os.SystemClock.elapsedRealtime() + 60000; controls(false); status.setText("验证码已发送到账号绑定邮箱，请注意查收。"); codeNotice = new AlertDialog.Builder(activity).setMessage("验证码已发送，请查收绑定邮箱").setPositiveButton("知道了", null).show(); }
            public void onError(String message) { if (alive()) { controls(false); status.setText(message); } }
        });
    }
    private void save() {
        if (busy || !loaded) return;
        String value = password.getText().toString();
        if (!value.matches("[0-9]{6}")) { password.setError("请输入六位数字支付密码"); return; }
        if (!value.equals(confirmation.getText().toString())) { confirmation.setError("两次输入不一致"); return; }
        if (configured && !code.getText().toString().matches("[0-9]{6}")) { code.setError("请输入邮箱验证码"); return; }
        JSONObject request = new JSONObject();
        try { request.put("password", value).put("confirmation", confirmation.getText().toString()); if (configured) request.put("code", code.getText().toString()); } catch (Exception e) { return; }
        controls(true);
        ApiClient.post(activity, configured ? "payment.password.change.email" : "payment.password.set", request, new ApiClient.Callback() {
            public void onSuccess(Object result) { if (!alive()) return; dialog.dismiss(); AppNotice.show(activity, "支付密码已保存"); }
            public void onError(String message) { if (!alive()) return; clearSecrets(); controls(false); status.setText(message); }
            public void onHttpError(int statusCode, String message) { onError(message); if (statusCode == 409 && alive()) load(); }
        });
    }
    private void clearSecrets() { if (password != null) password.setText(""); if (confirmation != null) confirmation.setText(""); if (code != null) code.setText(""); }
}
