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
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.telephony.SmsManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    private static final String SETTINGS_PREFS = "sms_send_settings";
    private static final String KEY_LANGUAGE = "app_language";

    private Button btnRead;
    private Button btnSend;
    private Button btnSettings;
    private Button btnHelp;
    private TextView txtFile;
    private TextView txtStatus;
    private TextView txtTableTitle;
    private TableLayout tableExcel;

    private static final int COL_USERNAME = 0;
    private static final int COL_PHONE = 1;
    private static final int COL_MESSAGE = 2;
    private static final int COL_REPLY = 3;
    private static final int COL_STATUS = 4;
    private static final int COLUMN_COUNT = XlsxSmsWorkbook.COLUMN_COUNT;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final XlsxSmsWorkbook workbook = new XlsxSmsWorkbook();
    private Uri selectedUri;
    private Runnable pendingAction;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(wrapLocale(newBase));
    }

    private static Context wrapLocale(Context context) {
        String lang = context.getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getString(KEY_LANGUAGE, "fa");
        Locale locale = new Locale(lang);
        Locale.setDefault(locale);
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        config.setLayoutDirection(locale);
        return context.createConfigurationContext(config);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnRead = findViewById(R.id.btnRead);
        btnSend = findViewById(R.id.btnSend);
        btnSettings = findViewById(R.id.btnSettings);
        btnHelp = findViewById(R.id.btnHelp);
        txtFile = findViewById(R.id.txtFile);
        txtStatus = findViewById(R.id.txtStatus);
        txtTableTitle = findViewById(R.id.txtTableTitle);
        tableExcel = findViewById(R.id.tableExcel);
        tableExcel.setStretchAllColumns(true);

        loadSavedUri();
        refreshFileDependentUi();

        btnRead.setOnClickListener(v -> {
            if (selectedUri == null) {
                pickExcelFile();
            } else {
                loadWorkbookPreview();
            }
        });
        btnSend.setOnClickListener(v -> ensurePermissionsAndRun(this::sendMessages));
        btnSettings.setOnClickListener(v -> showSettingsDialog());
        btnHelp.setOnClickListener(v -> showGuideDialog());
    }

    private void refreshFileDependentUi() {
        refreshFileLabel();
        boolean hasFile = selectedUri != null;
        btnSend.setEnabled(hasFile);
        btnRead.setText(hasFile ? getString(R.string.btn_update_file) : getString(R.string.btn_select_file));
    }

    private String getSavedLanguage() {
        return getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getString(KEY_LANGUAGE, "fa");
    }

    private void setSavedLanguage(String lang) {
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit().putString(KEY_LANGUAGE, lang).apply();
    }

    private void showSettingsDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        RadioGroup rgLanguage = dialogView.findViewById(R.id.rgLanguage);
        RadioButton rbFarsi = dialogView.findViewById(R.id.rbFarsi);
        RadioButton rbEnglish = dialogView.findViewById(R.id.rbEnglish);

        final String currentLang = getSavedLanguage();
        if ("en".equals(currentLang)) {
            rbEnglish.setChecked(true);
        } else {
            rbFarsi.setChecked(true);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_title)
                .setView(dialogView)
                .setNegativeButton(R.string.dialog_close, null)
                .create();

        rgLanguage.setOnCheckedChangeListener((group, checkedId) -> {
            String newLang = checkedId == R.id.rbEnglish ? "en" : "fa";
            if (!newLang.equals(currentLang)) {
                setSavedLanguage(newLang);
                dialog.dismiss();
                recreate();
            }
        });

        dialog.show();
    }

    private final android.os.Handler refreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshTask = () -> {
        if (selectedUri == null || executor.isShutdown()) {
            return;
        }
        executor.execute(() -> {
            try {
                List<String[]> rows;
                synchronized (XlsxSmsWorkbook.FILE_LOCK) {
                    rows = workbook.read(this, selectedUri);
                }
                final List<String[]> finalRows = rows;
                runOnUiThread(() -> renderTable(finalRows));
            } catch (Exception e) {
                Log.w(TAG, "Auto refresh failed: " + e.getMessage());
            }
        });
    };

    private void scheduleTableRefresh() {
        refreshHandler.removeCallbacks(refreshTask);
        refreshHandler.postDelayed(refreshTask, 800);
    }

    @Override
    protected void onStart() {
        super.onStart();
        SmsSentReceiver.setListener(() -> runOnUiThread(this::scheduleTableRefresh));
        if (selectedUri != null && tableExcel.getChildCount() > 0) {
            scheduleTableRefresh();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        SmsSentReceiver.setListener(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        refreshHandler.removeCallbacks(refreshTask);
        executor.shutdownNow();
    }

    private void showGuideDialog() {
        String guideText = loadGuideText();
        new AlertDialog.Builder(this)
                .setTitle(R.string.guide_title)
                .setMessage(guideText)
                .setPositiveButton(R.string.dialog_close, null)
                .show();
    }

    private String loadGuideText() {
        String assetName = "en".equals(getSavedLanguage()) ? "README_en.md" : "README.md";
        try (InputStream input = getAssets().open(assetName);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (builder.length() > 0) {
                    builder.append("\n");
                }
                builder.append(line);
            }
            return builder.toString();
        } catch (Exception e) {
            Log.e(TAG, "Guide load error", e);
            return getString(R.string.summary);
        }
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
                showAlert(getString(R.string.err_file_not_chosen));
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
            refreshFileDependentUi();
            loadWorkbookPreview();
        }
    }

    private void loadWorkbookPreview() {
        if (selectedUri == null) {
            showAlert(getString(R.string.err_no_file_selected));
            return;
        }
        executor.execute(() -> {
            try {
                List<String[]> rows;
                synchronized (XlsxSmsWorkbook.FILE_LOCK) {
                    rows = workbook.read(this, selectedUri);
                }
                runOnUiThread(() -> {
                    showStatus(getString(R.string.status_loaded_format, rows.size()));
                    renderTable(rows);
                    showAlert(getString(R.string.msg_file_loaded), getString(R.string.status_ready));
                });
            } catch (Exception e) {
                Log.e(TAG, "Load error", e);
                runOnUiThread(() -> showAlert(getString(R.string.err_load_format, safeMessage(e)), getString(R.string.title_error)));
            }
        });
    }

    private void sendMessages() {
        if (selectedUri == null) {
            showAlert(getString(R.string.err_no_file_selected));
            return;
        }
        executor.execute(() -> {
            try {
                final Uri uri = selectedUri;
                final List<String[]> rows;
                final List<Object[]> toSend = new ArrayList<>();
                int errorCount = 0;
                synchronized (XlsxSmsWorkbook.FILE_LOCK) {
                    rows = workbook.read(this, uri);
                    String[] templateRow = rows.isEmpty() ? new String[0] : normalizeRow(rows.get(0));
                    long minSent = Long.MAX_VALUE;
                    for (int i = 0; i < rows.size(); i++) {
                        String[] r = normalizeRow(rows.get(i));
                        if (isHeaderRow(i, r)) {
                            continue;
                        }
                        String cell = safeTrim(r[COL_MESSAGE]);
                        if (!safeTrim(r[COL_PHONE]).isEmpty() && XlsxSmsWorkbook.SENT_DATE_PATTERN.matcher(cell).matches()) {
                            long t = parseSentMillis(cell);
                            if (t >= 0 && t < minSent) {
                                minSent = t;
                            }
                        }
                    }
                    List<InboxMsg> inbox = minSent == Long.MAX_VALUE ? new ArrayList<>() : readInbox(minSent);
                    for (int i = 0; i < rows.size(); i++) {
                        String[] row = normalizeRow(rows.get(i));
                        rows.set(i, row);
                        if (isHeaderRow(i, row)) {
                            continue;
                        }
                        String rawPhone = safeTrim(row[COL_PHONE]);
                        String messageCell = safeTrim(row[COL_MESSAGE]);
                        if (rawPhone.isEmpty() || messageCell.isEmpty()) {
                            continue;
                        }
                        String phone = normalizePhone(rawPhone);
                        boolean phoneOk = phone.startsWith("+") || (phone.startsWith("0") && phone.length() == 11);
                        String messageToSend;
                        if (XlsxSmsWorkbook.SENT_DATE_PATTERN.matcher(messageCell).matches()) {
                            if (!phoneOk) {
                                continue;
                            }
                            long sentMillis = parseSentMillis(messageCell);
                            String key = lastTen(phone);
                            if (sentMillis < 0 || key.length() < 10) {
                                continue;
                            }
                            int replyDigit = -1;
                            for (InboxMsg m : inbox) {
                                if (m.date > sentMillis && key.equals(m.key)) {
                                    int d = singleDigitOneToNine(m.body);
                                    if (d > 0) {
                                        replyDigit = d;
                                        break;
                                    }
                                }
                            }
                            if (replyDigit < 0) {
                                continue;
                            }
                            row[COL_REPLY] = String.valueOf(replyDigit);
                            int phraseIndex = COL_STATUS + replyDigit;
                            messageToSend = phraseIndex < templateRow.length ? safeTrim(templateRow[phraseIndex]) : "";
                            if (messageToSend.isEmpty()) {
                                row[COL_STATUS] = getString(R.string.err_empty_template_format, replyDigit);
                                errorCount++;
                                continue;
                            }
                        } else {
                            if (!phoneOk) {
                                row[COL_STATUS] = getString(R.string.err_invalid_phone_format, rawPhone);
                                errorCount++;
                                continue;
                            }
                            int digit = singleDigitOneToNine(messageCell);
                            if (digit > 0) {
                                int phraseIndex = COL_STATUS + digit;
                                messageToSend = phraseIndex < templateRow.length ? safeTrim(templateRow[phraseIndex]) : "";
                                if (messageToSend.isEmpty()) {
                                    row[COL_STATUS] = getString(R.string.err_empty_template_format, digit);
                                    errorCount++;
                                    continue;
                                }
                            } else {
                                messageToSend = messageCell;
                            }
                        }
                        row[COL_STATUS] = getString(R.string.status_pending_send);
                        toSend.add(new Object[]{i, phone, messageToSend});
                    }
                    workbook.write(this, uri, rows);
                }
                runOnUiThread(() -> renderTable(rows));

                SmsManager smsManager = SmsManager.getDefault();
                int queuedCount = 0;
                for (Object[] item : toSend) {
                    int i = (Integer) item[0];
                    String phone = (String) item[1];
                    String messageToSend = (String) item[2];
                    ArrayList<String> parts = smsManager.divideMessage(messageToSend);
                    if (parts.size() > 1) {
                        smsManager.sendMultipartTextMessage(phone, null, parts, null,
                                PendingIntentHelper.createSentIntents(this, i, phone, uri.toString(), parts.size()));
                    } else {
                        smsManager.sendTextMessage(phone, null, messageToSend,
                                PendingIntentHelper.createSentIntent(this, i, phone, uri.toString()),
                                null);
                    }
                    queuedCount++;
                }
                final int finalQueuedCount = queuedCount;
                final int finalErrorCount = errorCount;
                runOnUiThread(() -> {
                    showStatus(getString(R.string.status_send_result_format, finalQueuedCount, finalErrorCount));
                    showAlert(getString(R.string.alert_send_result_format, finalQueuedCount, finalErrorCount), getString(R.string.title_send_result));
                });
            } catch (Exception e) {
                Log.e(TAG, "Send error", e);
                runOnUiThread(() -> showAlert(getString(R.string.err_send_format, safeMessage(e)), getString(R.string.title_error)));
            }
        });
    }

    private static final class InboxMsg {
        final String key;
        final long date;
        final String body;

        InboxMsg(String key, long date, String body) {
            this.key = key;
            this.date = date;
            this.body = body;
        }
    }

    private List<InboxMsg> readInbox(long minDate) {
        List<InboxMsg> list = new ArrayList<>();
        try (Cursor c = getContentResolver().query(Uri.parse("content://sms/inbox"),
                new String[]{"address", "date", "body"}, "date > ?",
                new String[]{String.valueOf(minDate)}, "date DESC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    String address = c.getString(0);
                    long date = c.getLong(1);
                    String body = c.getString(2);
                    String key = lastTen(address);
                    if (key.length() >= 10 && body != null) {
                        list.add(new InboxMsg(key, date, body));
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Inbox read failed: " + e.getMessage());
        }
        return list;
    }

    private static String lastTen(String number) {
        if (number == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < number.length(); i++) {
            char ch = number.charAt(i);
            if (Character.isDigit(ch)) {
                int v = Character.getNumericValue(ch);
                if (v >= 0 && v <= 9) {
                    digits.append((char) ('0' + v));
                }
            }
        }
        String d = digits.toString();
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    private static long parseSentMillis(String text) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(XlsxSmsWorkbook.SENT_DATE_FORMAT, Locale.US);
            f.setLenient(false);
            java.util.Date d = f.parse(text);
            return d == null ? -1 : d.getTime();
        } catch (Exception e) {
            return -1;
        }
    }

    private int singleDigitOneToNine(String reply) {
        if (reply == null) {
            return -1;
        }
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < reply.length(); i++) {
            char ch = reply.charAt(i);
            int type = Character.getType(ch);
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)
                    || type == Character.FORMAT || type == Character.CONTROL
                    || ch == '\uFEFF') {
                continue;
            }
            cleaned.append(ch);
        }
        String text = cleaned.toString();
        if (text.matches("[0-9\\u06F0-\\u06F9\\u0660-\\u0669][.\\u066B]0+")) {
            text = text.substring(0, 1);
        }
        if (text.length() != 1) {
            return -1;
        }
        char ch = text.charAt(0);
        int value;
        if (ch >= '\u06F0' && ch <= '\u06F9') {
            value = ch - '\u06F0';
        } else if (ch >= '\u0660' && ch <= '\u0669') {
            value = ch - '\u0660';
        } else if (ch >= '0' && ch <= '9') {
            value = ch - '0';
        } else {
            return -1;
        }
        return (value >= 1 && value <= 9) ? value : -1;
    }

    private void ensurePermissionsAndRun(Runnable action) {
        if (hasSmsPermissions()) {
            action.run();
            return;
        }
        pendingAction = action;
        requestPermissions(new String[]{Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS}, REQ_PERMISSIONS);
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
                showAlert(getString(R.string.err_permissions_denied), getString(R.string.title_error));
                showStatus(getString(R.string.status_permissions_required));
            }
            pendingAction = null;
        }
    }

    private boolean hasSmsPermissions() {
        return checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
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
            txtFile.setText(getString(R.string.label_selected_file_format, getDisplayName(selectedUri)));
        }
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    String name = cursor.getString(nameIndex);
                    if (name != null && !name.trim().isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to resolve display name: " + e.getMessage());
        }
        String lastSegment = uri.getLastPathSegment();
        return lastSegment != null ? lastSegment : uri.toString();
    }

    private void showStatus(String message) {
        txtStatus.setText(message);
    }

    private void renderTable(List<String[]> rows) {
        tableExcel.removeAllViews();
        String[] headers = {
                getString(R.string.table_header_username),
                getString(R.string.table_header_message),
                getString(R.string.table_header_reply),
                getString(R.string.table_header_status)
        };
        tableExcel.addView(buildTableRow(headers, true));
        if (rows != null) {
            for (String[] row : rows) {
                String[] normalized = normalizeRow(row);
                String[] display = {
                        normalized[COL_USERNAME],
                        normalized[COL_MESSAGE],
                        normalized[COL_REPLY],
                        normalized[COL_STATUS]
                };
                tableExcel.addView(buildTableRow(display, false));
            }
        }
        txtTableTitle.setVisibility(View.VISIBLE);
    }

    private TableRow buildTableRow(String[] values, boolean isHeader) {
        TableRow tableRow = new TableRow(this);
        for (String value : values) {
            TextView cell = new TextView(this);
            cell.setText(value == null ? "" : value);
            cell.setPadding(dp(4), dp(4), dp(4), dp(4));
            cell.setGravity(Gravity.CENTER);
            cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
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
                int value = Character.getNumericValue(ch);
                if (value >= 0 && value <= 9) {
                    builder.append((char) ('0' + value));
                }
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
        String[] normalized = new String[COLUMN_COUNT];
        if (row != null) {
            for (int i = 0; i < Math.min(COLUMN_COUNT, row.length); i++) {
                normalized[i] = row[i] == null ? "" : row[i];
            }
        }
        for (int i = 0; i < COLUMN_COUNT; i++) {
            if (normalized[i] == null) normalized[i] = "";
        }
        return normalized;
    }

    private boolean isHeaderRow(int index, String[] row) {
        if (index != 0) {
            return false;
        }
        String digitsOnly = safeTrim(row[COL_PHONE]).replaceAll("[^0-9]", "");
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
    }
}
