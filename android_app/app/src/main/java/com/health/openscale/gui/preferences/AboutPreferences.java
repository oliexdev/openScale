/* Copyright (C) 2014  olie.xdev <olie.xdev@googlemail.com>
*
*    This program is free software: you can redistribute it and/or modify
*    it under the terms of the GNU General Public License as published by
*    the Free Software Foundation, either version 3 of the License, or
*    (at your option) any later version.
*
*    This program is distributed in the hope that it will be useful,
*    but WITHOUT ANY WARRANTY; without even the implied warranty of
*    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*    GNU General Public License for more details.
*
*    You should have received a copy of the GNU General Public License
*    along with this program.  If not, see <http://www.gnu.org/licenses/>
*/
package com.health.openscale.gui.preferences;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.util.Log;

import com.health.openscale.BuildConfig;
import com.health.openscale.R;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import timber.log.Timber;

import static android.app.Activity.RESULT_OK;

public class AboutPreferences extends PreferenceFragment {
    private static final String KEY_APP_VERSION = "pref_app_version";
    private static final String KEY_DEBUG_LOG = "debug_log";

    private static final int DEBUG_LOG_REQUEST = 100;

    class FileDebugTree extends Timber.DebugTree {
        PrintWriter writer;
        DateFormat format;

        FileDebugTree(OutputStream output) {
            writer = new PrintWriter(output, true);
            format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        }

        void close() {
            writer.close();
        }

        private String priorityToString(int priority) {
            switch (priority) {
                case Log.ASSERT:
                    return "Assert";
                case Log.ERROR:
                    return "Error";
                case Log.WARN:
                    return "Warning";
                case Log.INFO:
                    return "Info";
                case Log.DEBUG:
                    return "Debug";
                case Log.VERBOSE:
                    return "Verbose";
            }
            return String.format("Unknown (%d)", priority);
        }

        @Override
        protected void log(int priority, String tag, String message, Throwable t) {
            writer.printf("%s %s %s: %s\n",
                    format.format(new Date()), priorityToString(priority), tag, message);
        }
    }

    private FileDebugTree getEnabledFileDebugTree() {
        for (Timber.Tree tree : Timber.forest()) {
            if (tree instanceof FileDebugTree) {
                return (FileDebugTree) tree;
            }
        }
        return null;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.about_preferences);

        findPreference(KEY_APP_VERSION).setSummary(
                String.format("v%s (%d)", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));

        Preference debugLog = findPreference(KEY_DEBUG_LOG);
        debugLog.setSummary(getEnabledFileDebugTree() != null
                ? R.string.info_is_enable : R.string.info_is_not_enable);
        debugLog.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                FileDebugTree tree = getEnabledFileDebugTree();
                if (tree != null) {
                    Timber.d("Debug log disabled");
                    tree.close();
                    Timber.uproot(tree);
                    preference.setSummary(R.string.info_is_not_enable);
                    return true;
                }

                DateFormat format = new SimpleDateFormat("yyyy-MM-dd_HH-mm");
                String fileName = String.format("openScale_%s.txt", format.format(new Date()));

                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TITLE, fileName);

                startActivityForResult(intent, DEBUG_LOG_REQUEST);

                return true;
            }
        });
    }

    private void startLogTo(Uri uri) {
        try {
            OutputStream output = getActivity().getContentResolver().openOutputStream(uri);
            Timber.plant(new FileDebugTree(output));
            findPreference(KEY_DEBUG_LOG).setSummary(R.string.info_is_enable);
            Timber.d("Debug log enabled (%s v%s (%d))",
                    getResources().getString(R.string.app_name),
                    BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE);
        }
        catch (IOException ex) {
            Timber.e(ex, "Failed to open debug log %s", uri.toString());
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == DEBUG_LOG_REQUEST && resultCode == RESULT_OK && data != null) {
            startLogTo(data.getData());
        }
    }
}
