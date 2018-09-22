package com.health.openscale.connector;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final String APP_ID = BuildConfig.APPLICATION_ID.replace(".connector", "");
        final String AUTHORITY = APP_ID + ".provider";
        final String REQUIRED_PERMISSION = APP_ID + ".READ_DATA";
        final int REQUEST_CODE = 1;

        if (ActivityCompat.checkSelfPermission(getApplicationContext(),
                REQUIRED_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{REQUIRED_PERMISSION}, REQUEST_CODE);
        } else {
            Uri usersUri = new Uri.Builder()
                    .scheme(ContentResolver.SCHEME_CONTENT)
                    .authority(AUTHORITY)
                    .path("users")
                    .build();
            Uri measurementsUri = new Uri.Builder()
                    .scheme(ContentResolver.SCHEME_CONTENT)
                    .authority(AUTHORITY)
                    .path("measurements")
                    .build();

            Cursor c = getContentResolver().query(
                    usersUri, null, null, null, null);

            StringBuilder s = new StringBuilder();

            try {
                int user = 0;
                while (c.moveToNext()) {
                    s.append("====== USER ");
                    s.append(++user).append("/").append(c.getCount());
                    s.append(" ======");
                    s.append(System.lineSeparator());

                    for (int i = 0; i < c.getColumnCount(); ++i) {
                        s.append(" - ").append(c.getColumnName(i));
                        s.append(": ").append(c.getString(i));
                        s.append(System.lineSeparator());
                    }

                    long userId = c.getLong(c.getColumnIndex(BaseColumns._ID));
                    Cursor m = getContentResolver().query(
                            ContentUris.withAppendedId(measurementsUri, userId),
                            null, null, null, null);

                    try {
                        int measurement = 0;
                        while (m.moveToNext()) {
                            s.append("++++++ MEASUREMENT ");
                            s.append(++measurement).append("/").append(m.getCount());
                            s.append(" ++++++");
                            s.append(System.lineSeparator());
                            for (int i = 0; i < m.getColumnCount(); ++i) {
                                s.append("  * ").append(m.getColumnName(i));
                                s.append(": ").append(m.getString(i));
                                s.append(System.lineSeparator());
                            }
                        }
                    }
                    finally {
                        m.close();
                    }
                }
            }
            finally {
                c.close();
            }
            TextView text = findViewById(R.id.mainText);
            text.setText(s.toString());
        }
    }
}
