package ir.example.smssend;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SmsSentReceiver extends BroadcastReceiver {

    public static final String ACTION_SMS_SENT = "ir.example.smssend.ACTION_SMS_SENT";
    public static final String EXTRA_ROW_INDEX = "extra_row_index";
    public static final String EXTRA_PHONE = "extra_phone";
    public static final String EXTRA_URI = "extra_uri";
    public static final String EXTRA_PART_INDEX = "extra_part_index";
    public static final String EXTRA_PART_COUNT = "extra_part_count";

    private static final String TAG = "SmsSentReceiver";
    private static final java.util.concurrent.ExecutorService WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private static volatile Runnable listener;

    public static void setListener(Runnable r) {
        listener = r;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        int rowIndex = intent.getIntExtra(EXTRA_ROW_INDEX, -1);
        String phone = intent.getStringExtra(EXTRA_PHONE);
        String uriString = intent.getStringExtra(EXTRA_URI);
        if (rowIndex < 0 || uriString == null || uriString.trim().isEmpty()) {
            Log.e(TAG, "Missing row index or uri");
            return;
        }
        Uri uri = Uri.parse(uriString);
        boolean success = getResultCode() == Activity.RESULT_OK;
        String status = resolveStatus(getResultCode());
        final Context appContext = context.getApplicationContext();
        final PendingResult pending = goAsync();
        WORKER.execute(() -> {
            try {
                XlsxStatusUpdater.updateStatus(appContext, uri, rowIndex, status, success);
            } catch (Exception e) {
                Log.e(TAG, "Failed to update status for row " + rowIndex + " phone=" + phone, e);
            } finally {
                Runnable l = listener;
                if (l != null) {
                    l.run();
                }
                pending.finish();
            }
        });
    }

    private String resolveStatus(int resultCode) {
        if (resultCode == Activity.RESULT_OK) {
            return "ارسال موفق";
        }
        if (resultCode == SmsManagerResults.RESULT_ERROR_GENERIC_FAILURE) {
            return "خطا: Generic failure";
        }
        if (resultCode == SmsManagerResults.RESULT_ERROR_NO_SERVICE) {
            return "خطا: No service";
        }
        if (resultCode == SmsManagerResults.RESULT_ERROR_NULL_PDU) {
            return "خطا: Null PDU";
        }
        if (resultCode == SmsManagerResults.RESULT_ERROR_RADIO_OFF) {
            return "خطا: Radio off";
        }
        if (resultCode == SmsManagerResults.RESULT_ERROR_LIMIT_EXCEEDED) {
            return "خطا: Limit exceeded";
        }
        return "خطا: code=" + resultCode;
    }

    private static final class SmsManagerResults {
        static final int RESULT_ERROR_GENERIC_FAILURE = 1;
        static final int RESULT_ERROR_RADIO_OFF = 2;
        static final int RESULT_ERROR_NULL_PDU = 3;
        static final int RESULT_ERROR_NO_SERVICE = 4;
        static final int RESULT_ERROR_LIMIT_EXCEEDED = 5;
    }

    static final class XlsxStatusUpdater {
        static void updateStatus(Context context, Uri uri, int rowIndex, String status, boolean success) throws Exception {
            XlsxSmsWorkbook workbook = new XlsxSmsWorkbook();
            synchronized (XlsxSmsWorkbook.FILE_LOCK) {
            List<String[]> rows = workbook.read(context, uri);
            if (rowIndex >= 0 && rowIndex < rows.size()) {
                int columnCount = XlsxSmsWorkbook.COLUMN_COUNT;
                String[] row = rows.get(rowIndex);
                if (row == null || row.length < columnCount) {
                    String[] fixed = new String[columnCount];
                    for (int i = 0; i < columnCount; i++) {
                        fixed[i] = row != null && i < row.length && row[i] != null ? row[i] : "";
                    }
                    row = fixed;
                }
                row[XlsxSmsWorkbook.COL_STATUS] = status;
                if (success) {
                    row[XlsxSmsWorkbook.COL_MESSAGE] = new SimpleDateFormat(XlsxSmsWorkbook.SENT_DATE_FORMAT, Locale.US).format(new Date());
                }
                rows.set(rowIndex, row);
                workbook.write(context, uri, rows);
            }
            }
        }
    }
}
