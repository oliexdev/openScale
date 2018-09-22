package com.health.openscale.connector;

import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final String REQUIRED_PERMISSION = "com.health.openscale.READ_DATA";
        final int REQUEST_CODE = 1;

        if (ActivityCompat.checkSelfPermission(getApplicationContext(),
                REQUIRED_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{REQUIRED_PERMISSION}, REQUEST_CODE);
        } else {
            Cursor c = getContentResolver().query(
                    Uri.parse("content://com.health.openscale.provider/user"),
                    null, null, null, null);
            StringBuilder s = new StringBuilder();
            s.append("=========== iterate all users ============" + System.lineSeparator());
            try {
                while (c.moveToNext()) {
                    s.append("------ new record ------" + System.lineSeparator());
                    for (int i = 0; i < c.getColumnCount(); i++) {
                        s.append("- " + c.getColumnName(i) + ": " + c.getString(i) + System.lineSeparator());
                    }
                    Cursor m = getContentResolver().query(
                            Uri.parse("content://com.health.openscale.provider/user/" + c.getString(0)
                            + "/measurements"), null, null, null, null);
                    s.append("measurements: " + m.getCount() + System.lineSeparator());
                    while (m.moveToNext()) {
                        s.append("-- new measurement --" + System.lineSeparator());
                        for (int j = 0; j < m.getColumnCount(); j++) {
                            if (!m.getString(j).equals("0")) {
                                s.append("  * " + m.getColumnName(j) + ": " + m.getString(j) + System.lineSeparator());
                            }
                        }
                    }
                    m.close();
                }
            } finally {
                c.close();
            }
            TextView text = findViewById(R.id.mainText);
            text.setText(s.toString());
        }
    }
}
