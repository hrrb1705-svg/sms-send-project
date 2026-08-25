package ir.example.smssend;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.util.List;

public class SmsSentReceiver extends BroadcastReceiver {

    public static final String ACTION_SMS_SENT = "ir.example.smssend.ACTION_SMS_SENT";
    public static final String EXTRA_ROW_INDEX = "extra_row_index";
    public static final String EXTRA_PHONE = "extra_phone";
    public static final String EXTRA_URI = "extra_uri";
    public static final String EXTRA_PART_INDEX = "extra_part_index";
    public static final String EXTRA_PART_COUNT = "extra_part_count";

    private static final String TAG = "SmsSentReceiver";

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
        String status = resolveStatus(getResultCode());
        try {
            XlsxStatusUpdater.updateStatus(context, uri, rowIndex, status);
        } catch (Exception e) {
            Log.e(TAG, "Failed to update status for row " + rowIndex + " phone=" + phone, e);
        }
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
        static void updateStatus(Context context, Uri uri, int rowIndex, String status) throws Exception {
            XlsxSmsWorkbook workbook = new XlsxSmsWorkbook();
            List<String[]> rows = workbook.read(context, uri);
            if (rowIndex >= 0 && rowIndex < rows.size()) {
                String[] row = rows.get(rowIndex);
                if (row == null || row.length < 4) {
                    String[] fixed = new String[4];
                    for (int i = 0; i < 4; i++) {
                        fixed[i] = row != null && i < row.length && row[i] != null ? row[i] : "";
                    }
                    row = fixed;
                }
                row[3] = status;
                rows.set(rowIndex, row);
                workbook.write(context, uri, rows);
            }
        }
    }
}
