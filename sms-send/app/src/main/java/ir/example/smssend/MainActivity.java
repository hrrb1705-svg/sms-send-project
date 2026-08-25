package ir.example.smssend;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String TAG = "sms-send";
    private static final int REQ_PICK_FILE = 1001;
    private static final int REQ_PERMISSIONS = 1002;
    private static final String PREFS = "sms_send_prefs";
    private static final String KEY_URI = "selected_uri";
    private static final String REPLY_STORE = "reply_store";

    private Button btnRead;
    private Button btnSend;
    private Button btnReceive;
    private TextView txtFile;
    private TextView txtStatus;
    private TextView txtTableTitle;
    private TableLayout tableExcel;
    private static final String[] TABLE_HEADERS = {"شماره موبایل", "متن پیام", "پاسخ دریافت‌کننده", "خطای احتمالی"};

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final XlsxSmsWorkbook workbook = new XlsxSmsWorkbook();
    private Uri selectedUri;
    private Runnable pendingAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnRead = findViewById(R.id.btnRead);
        btnSend = findViewById(R.id.btnSend);
        btnReceive = findViewById(R.id.btnReceive);
        txtFile = findViewById(R.id.txtFile);
        txtStatus = findViewById(R.id.txtStatus);
        txtTableTitle = findViewById(R.id.txtTableTitle);
        tableExcel = findViewById(R.id.tableExcel);

        loadSavedUri();
        refreshFileLabel();

        btnRead.setOnClickListener(v -> pickExcelFile());
        btnSend.setOnClickListener(v -> ensurePermissionsAndRun(this::sendMessages));
        btnReceive.setOnClickListener(v -> ensurePermissionsAndRun(this::showReplyStatsAndWriteToWorkbook));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void pickExcelFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQ_PICK_FILE) {
            selectedUri = data.getData();
            if (selectedUri == null) {
                showAlert("فایل انتخاب نشد.");
                return;
            }
            try {
                final int takeFlags = data.getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(selectedUri, takeFlags);
            } catch (Exception e) {
                Log.w(TAG, "Persist permission not granted: " + e.getMessage());
            }
            saveSelectedUri();
            refreshFileLabel();
            loadWorkbookPreview();
        }
    }

    private void loadWorkbookPreview() {
        if (selectedUri == null) {
            showAlert("ابتدا فایل اکسل را انتخاب کنید.");
            return;
        }
        executor.execute(() -> {
            try {
                List<String[]> rows = workbook.read(this, selectedUri);
                runOnUiThread(() -> {
                    showStatus("فایل بارگذاری شد. تعداد ردیف‌ها: " + rows.size());
                    renderTable(rows);
                    showAlert("فایل بارگذاری شد.", "آماده");
                });
            } catch (Exception e) {
                Log.e(TAG, "Load error", e);
                runOnUiThread(() -> showAlert("خطا در خواندن فایل: " + safeMessage(e), "خطا"));
            }
        });
    }

    private void sendMessages() {
        if (selectedUri == null) {
            showAlert("ابتدا فایل اکسل را انتخاب کنید.");
            return;
        }
        executor.execute(() -> {
            try {
                List<String[]> rows = workbook.read(this, selectedUri);
                int queuedCount = 0;
                int errorCount = 0;
                SmsManager smsManager = SmsManager.getDefault();
                for (int i = 0; i < rows.size(); i++) {
                    String[] row = normalizeRow(rows.get(i));
                    if (isHeaderRow(i, row)) {
                        continue;
                    }
                    String rawPhone = safeTrim(row[0]);
                    String phone = normalizePhone(rawPhone);
                    String message = safeTrim(row[1]);
                    if (phone.isEmpty()) {
                        row[3] = "شماره موبایل خالی است";
                        rows.set(i, row);
                        errorCount++;
                        continue;
                    }
                    if (!phone.startsWith("+") && !(phone.startsWith("0") && phone.length() == 11)) {
                        row[3] = "شماره موبایل نامعتبر است: " + rawPhone;
                        rows.set(i, row);
                        errorCount++;
                        continue;
                    }
                    if (message.isEmpty()) {
                        row[3] = "متن پیام خالی است";
                        rows.set(i, row);
                        errorCount++;
                        continue;
                    }
                    row[3] = "در انتظار نتیجه ارسال";
                    rows.set(i, row);
                    Intent sentIntent = new Intent(this, SmsSentReceiver.class)
                            .setAction(SmsSentReceiver.ACTION_SMS_SENT)
                            .putExtra(SmsSentReceiver.EXTRA_ROW_INDEX, i)
                            .putExtra(SmsSentReceiver.EXTRA_PHONE, phone)
                            .putExtra(SmsSentReceiver.EXTRA_URI, selectedUri.toString());
                    int requestCode = 10000 + i;
                    PendingIntentHelper.sendBroadcast(this, requestCode, sentIntent);
                    if (safeTrim(phone).length() > 0) {
                        ArrayList<String> parts = smsManager.divideMessage(message);
                        if (parts.size() > 1) {
                            smsManager.sendMultipartTextMessage(phone, null, parts, null,
                                    PendingIntentHelper.createSentIntents(this, i, phone, selectedUri.toString(), parts.size()));
                        } else {
                            smsManager.sendTextMessage(phone, null, message,
                                    PendingIntentHelper.createSentIntent(this, i, phone, selectedUri.toString()),
                                    null);
                        }
                        queuedCount++;
                    }
                }
                workbook.write(this, selectedUri, rows);
                final int finalQueuedCount = queuedCount;
                final int finalErrorCount = errorCount;
                runOnUiThread(() -> {
                    showStatus(String.format(Locale.getDefault(), "ارسال ثبت شد. در صف: %d ، خطا: %d", finalQueuedCount, finalErrorCount));
                    renderTable(rows);
                    showAlert(String.format(Locale.getDefault(), "ارسال ثبت شد.\nدر صف: %d\nخطا: %d", finalQueuedCount, finalErrorCount), "نتیجه ارسال");
                });
            } catch (Exception e) {
                Log.e(TAG, "Send error", e);
                runOnUiThread(() -> showAlert("خطا در ارسال پیام‌ها: " + safeMessage(e), "خطا"));
            }
        });
    }

    private void showReplyStatsAndWriteToWorkbook() {
        if (selectedUri == null) {
            showAlert("ابتدا فایل اکسل را انتخاب کنید.");
            return;
        }
        executor.execute(() -> {
            try {
                List<String[]> rows = workbook.read(this, selectedUri);
                SharedPreferences prefs = getSharedPreferences(REPLY_STORE, MODE_PRIVATE);
                String json = prefs.getString("data", "{}");
                JSONObject store = new JSONObject(json == null ? "{}" : json);
                int matched = 0;
                for (int i = 0; i < rows.size(); i++) {
                    String[] row = normalizeRow(rows.get(i));
                    if (isHeaderRow(i, row)) {
                        continue;
                    }
                    String phone = safeTrim(row[0]);
                    if (phone.isEmpty()) {
                        continue;
                    }
                    String reply = findReplyByNormalizedPhone(store, phone);
                    if (!reply.isEmpty()) {
                        row[2] = reply;
                        rows.set(i, row);
                        matched++;
                    }
                }
                workbook.write(this, selectedUri, rows);
                final int finalMatched = matched;
                runOnUiThread(() -> {
                    showStatus("پاسخ‌ها در فایل ثبت شد. تعداد ردیف‌های به‌روزشده: " + finalMatched);
                    renderTable(rows);
                    showAlert(String.format(Locale.getDefault(), "آمار پاسخ‌ها:\nتعداد ردیف‌های به‌روزشده: %d", finalMatched), "پاسخ‌ها");
                });
            } catch (Exception e) {
                Log.e(TAG, "Reply write error", e);
                runOnUiThread(() -> showAlert("خطا در ثبت پاسخ‌ها: " + safeMessage(e), "خطا"));
            }
        });
    }

    private String findReplyByNormalizedPhone(JSONObject store, String phone) {
        String normalized = normalizePhone(phone);
        if (normalized.isEmpty()) {
            return "";
        }
        String reply = store.optString(normalized, "");
        if (!reply.isEmpty()) {
            return reply;
        }
        String raw = store.optString(phone, "");
        return raw == null ? "" : raw;
    }

    private void ensurePermissionsAndRun(Runnable action) {
        if (hasSmsPermissions()) {
            action.run();
            return;
        }
        pendingAction = action;
        requestPermissions(new String[]{Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS}, REQ_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (hasSmsPermissions()) {
                if (pendingAction != null) {
                    pendingAction.run();
                }
            } else {
                showAlert("مجوزهای پیامک داده نشد.", "خطا");
                showStatus("مجوزهای پیامک لازم است.");
            }
            pendingAction = null;
        }
    }

    private boolean hasSmsPermissions() {
        return checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private void saveSelectedUri() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putString(KEY_URI, selectedUri == null ? null : selectedUri.toString()).apply();
    }

    private void loadSavedUri() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String value = prefs.getString(KEY_URI, null);
        if (value != null) {
            try {
                selectedUri = Uri.parse(value);
            } catch (Exception ignored) {
                selectedUri = null;
            }
        }
    }

    private void refreshFileLabel() {
        if (selectedUri == null) {
            txtFile.setText(getString(R.string.no_file));
        } else {
            txtFile.setText("فایل انتخاب‌شده: " + selectedUri.toString());
        }
    }

    private void showStatus(String message) {
        txtStatus.setText(message);
    }

    private void renderTable(List<String[]> rows) {
        tableExcel.removeAllViews();
        tableExcel.addView(buildTableRow(TABLE_HEADERS, true));
        if (rows != null) {
            for (String[] row : rows) {
                String[] normalized = normalizeRow(row);
                tableExcel.addView(buildTableRow(normalized, false));
            }
        }
        txtTableTitle.setVisibility(View.VISIBLE);
    }

    private TableRow buildTableRow(String[] values, boolean isHeader) {
        TableRow tableRow = new TableRow(this);
        for (String value : values) {
            TextView cell = new TextView(this);
            cell.setText(value == null ? "" : value);
            cell.setPadding(dp(10), dp(6), dp(10), dp(6));
            cell.setMinWidth(dp(90));
            cell.setGravity(Gravity.CENTER);
            cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            cell.setSingleLine(false);
            if (isHeader) {
                cell.setTypeface(cell.getTypeface(), android.graphics.Typeface.BOLD);
                cell.setBackgroundColor(0xFFE0E0E0);
            } else {
                cell.setBackgroundColor(0xFFFAFAFA);
            }
            tableRow.addView(cell);
        }
        return tableRow;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private void showAlert(String message) {
        showAlert(message, getString(R.string.app_name));
    }

    private void showAlert(String message, String title) {
        runOnUiThread(() -> {
            if (isFinishing()) {
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        });
    }

    static String normalizePhone(String phone) {
        if (phone == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < phone.length(); i++) {
            char ch = phone.charAt(i);
            if (Character.isDigit(ch)) {
                builder.append(ch);
            } else if (ch == '+' && builder.length() == 0) {
                builder.append(ch);
            }
        }
        String normalized = builder.toString();
        if (normalized.startsWith("+98")) {
            normalized = "0" + normalized.substring(3);
        } else if (normalized.startsWith("98") && normalized.length() > 2) {
            normalized = "0" + normalized.substring(2);
        } else if (normalized.startsWith("0098")) {
            normalized = "0" + normalized.substring(4);
        }
        if (normalized.startsWith("0") && normalized.length() > 11) {
            normalized = normalized.substring(0, 11);
        }
        return normalized;
    }

    private String[] normalizeRow(String[] row) {
        String[] normalized = new String[4];
        if (row != null) {
            for (int i = 0; i < Math.min(4, row.length); i++) {
                normalized[i] = row[i] == null ? "" : row[i];
            }
        }
        for (int i = 0; i < 4; i++) {
            if (normalized[i] == null) normalized[i] = "";
        }
        return normalized;
    }

    private boolean isHeaderRow(int index, String[] row) {
        if (index != 0) {
            return false;
        }
        String digitsOnly = safeTrim(row[0]).replaceAll("[^0-9]", "");
        return digitsOnly.length() < 7;
    }

    private String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    private String safeMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return msg.trim();
    }

    static final class PendingIntentHelper {
        static android.app.PendingIntent createSentIntent(Context context, int rowIndex, String phone, String uri) {
            Intent intent = new Intent(context, SmsSentReceiver.class)
                    .setAction(SmsSentReceiver.ACTION_SMS_SENT)
                    .putExtra(SmsSentReceiver.EXTRA_ROW_INDEX, rowIndex)
                    .putExtra(SmsSentReceiver.EXTRA_PHONE, phone)
                    .putExtra(SmsSentReceiver.EXTRA_URI, uri);
            return android.app.PendingIntent.getBroadcast(
                    context,
                    10000 + rowIndex,
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
            );
        }

        static ArrayList<android.app.PendingIntent> createSentIntents(Context context, int rowIndex, String phone, String uri, int count) {
            ArrayList<android.app.PendingIntent> list = new ArrayList<>();
            for (int part = 0; part < count; part++) {
                Intent intent = new Intent(context, SmsSentReceiver.class)
                        .setAction(SmsSentReceiver.ACTION_SMS_SENT)
                        .putExtra(SmsSentReceiver.EXTRA_ROW_INDEX, rowIndex)
                        .putExtra(SmsSentReceiver.EXTRA_PHONE, phone)
                        .putExtra(SmsSentReceiver.EXTRA_URI, uri)
                        .putExtra(SmsSentReceiver.EXTRA_PART_INDEX, part)
                        .putExtra(SmsSentReceiver.EXTRA_PART_COUNT, count);
                list.add(android.app.PendingIntent.getBroadcast(
                        context,
                        10000 + rowIndex * 31 + part,
                        intent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
                ));
            }
            return list;
        }

        static void sendBroadcast(Context context, int requestCode, Intent intent) {
            android.app.PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
            );
        }
    }
}
