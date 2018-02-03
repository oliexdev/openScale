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
package com.health.openscale.gui.fragments;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.gui.activities.DataEntryActivity;
import com.health.openscale.gui.views.BMIMeasurementView;
import com.health.openscale.gui.views.BMRMeasurementView;
import com.health.openscale.gui.views.BoneMeasurementView;
import com.health.openscale.gui.views.CommentMeasurementView;
import com.health.openscale.gui.views.DateMeasurementView;
import com.health.openscale.gui.views.FatMeasurementView;
import com.health.openscale.gui.views.HipMeasurementView;
import com.health.openscale.gui.views.LBWMeasurementView;
import com.health.openscale.gui.views.MeasurementView;
import com.health.openscale.gui.views.MuscleMeasurementView;
import com.health.openscale.gui.views.TimeMeasurementView;
import com.health.openscale.gui.views.WHRMeasurementView;
import com.health.openscale.gui.views.WHtRMeasurementView;
import com.health.openscale.gui.views.WaistMeasurementView;
import com.health.openscale.gui.views.WaterMeasurementView;
import com.health.openscale.gui.views.WeightMeasurementView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import lecho.lib.hellocharts.util.ChartUtils;

import static android.util.TypedValue.COMPLEX_UNIT_DIP;

public class TableFragment extends Fragment implements FragmentUpdateListener {
    private View tableView;
    private ListView tableDataView;
    private LinearLayout tableHeaderView;
    private SharedPreferences prefs;
    private LinearLayout subpageView;

    private ArrayList <MeasurementView> measurementsList;

    private int selectedSubpageNr;
    private static String SELECTED_SUBPAGE_NR_KEY = "selectedSubpageNr";

    public TableFragment() {

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
    {
        tableView = inflater.inflate(R.layout.fragment_table, container, false);

        subpageView = (LinearLayout) tableView.findViewById(R.id.subpageView);

        tableDataView = (ListView) tableView.findViewById(R.id.tableDataView);
        tableHeaderView = (LinearLayout) tableView.findViewById(R.id.tableHeaderView);

        tableView.findViewById(R.id.btnImportData).setOnClickListener(new onClickListenerImport());
        tableView.findViewById(R.id.btnExportData).setOnClickListener(new onClickListenerExport());

        measurementsList = new ArrayList<>();

        measurementsList.add(new DateMeasurementView(tableView.getContext()));
        measurementsList.add(new TimeMeasurementView(tableView.getContext()));
        measurementsList.add(new WeightMeasurementView(tableView.getContext()));
        measurementsList.add(new BMIMeasurementView(tableView.getContext()));
        measurementsList.add(new WaterMeasurementView(tableView.getContext()));
        measurementsList.add(new MuscleMeasurementView(tableView.getContext()));
        measurementsList.add(new LBWMeasurementView(tableView.getContext()));
        measurementsList.add(new FatMeasurementView(tableView.getContext()));
        measurementsList.add(new BoneMeasurementView(tableView.getContext()));
        measurementsList.add(new WaistMeasurementView(tableView.getContext()));
        measurementsList.add(new WHtRMeasurementView(tableView.getContext()));
        measurementsList.add(new HipMeasurementView(tableView.getContext()));
        measurementsList.add(new WHRMeasurementView(tableView.getContext()));
        measurementsList.add(new BMRMeasurementView(tableView.getContext()));
        measurementsList.add(new CommentMeasurementView(tableView.getContext()));

        for (MeasurementView measurement : measurementsList) {
            measurement.setUpdateViews(false);
        }

        prefs = PreferenceManager.getDefaultSharedPreferences(tableView.getContext());

        if (savedInstanceState == null) {
            selectedSubpageNr = 0;
        }
        else {
            selectedSubpageNr = savedInstanceState.getInt(SELECTED_SUBPAGE_NR_KEY);
        }

        OpenScale.getInstance(getContext()).registerFragment(this);

        return tableView;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(SELECTED_SUBPAGE_NR_KEY, selectedSubpageNr);
    }

    @Override
    public void updateOnView(List<ScaleMeasurement> scaleMeasurementList)
    {
        tableDataView.setAdapter(new ListViewAdapter(new ArrayList<HashMap<Integer, Spanned>>())); // delete all data in the table with an empty adapter array list

        if (scaleMeasurementList.isEmpty()) {
            return;
        }

        final int maxSize = 50;

        int subpageCount = (int)Math.ceil(scaleMeasurementList.size() / (double)maxSize);

        subpageView.removeAllViews();

        Button moveSubpageLeft = new Button(tableView.getContext());
        moveSubpageLeft.setText("<");
        moveSubpageLeft.setPadding(0,0,0,0);
        moveSubpageLeft.setTextColor(Color.WHITE);
        moveSubpageLeft.setBackground(ContextCompat.getDrawable(tableView.getContext(), R.drawable.flat_selector));
        moveSubpageLeft.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        moveSubpageLeft.getLayoutParams().height = pxImageDp(20);
        moveSubpageLeft.getLayoutParams().width = pxImageDp(50);
        moveSubpageLeft.setOnClickListener(new onClickListenerMoveSubpageLeft());
        moveSubpageLeft.setEnabled(selectedSubpageNr > 0);
        subpageView.addView(moveSubpageLeft);

        for (int i=0; i<subpageCount; i++) {
            TextView subpageNrView = new TextView(tableView.getContext());
            subpageNrView.setOnClickListener(new onClickListenerSubpageSelect());
            subpageNrView.setText(Integer.toString(i+1));
            subpageNrView.setPadding(10, 10, 20, 10);

            subpageView.addView(subpageNrView);
        }
        if (subpageView.getChildCount() > 1) {
            TextView selectedSubpageNrView = (TextView) subpageView.getChildAt(selectedSubpageNr + 1);
            if (selectedSubpageNrView != null) {
                selectedSubpageNrView.setTypeface(null, Typeface.BOLD);
                selectedSubpageNrView.setTextColor(ChartUtils.COLOR_BLUE);
            }
        }

        Button moveSubpageRight = new Button(tableView.getContext());
        moveSubpageRight.setText(">");
        moveSubpageRight.setPadding(0,0,0,0);
        moveSubpageRight.setTextColor(Color.WHITE);
        moveSubpageRight.setBackground(ContextCompat.getDrawable(tableView.getContext(), R.drawable.flat_selector));
        moveSubpageRight.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        moveSubpageRight.getLayoutParams().height = pxImageDp(20);
        moveSubpageRight.getLayoutParams().width = pxImageDp(50);
        moveSubpageRight.setOnClickListener(new onClickListenerMoveSubpageRight());
        moveSubpageRight.setEnabled(selectedSubpageNr + 1 < subpageCount);
        subpageView.addView(moveSubpageRight);

        if (subpageCount <= 1) {
            subpageView.setVisibility(View.GONE);
        } else {
            subpageView.setVisibility(View.VISIBLE);
        }

        tableHeaderView.removeAllViews();

        for (MeasurementView measurement : measurementsList) {
            measurement.updatePreferences(prefs);

            if (measurement.isVisible()) {
                ImageView headerIcon = new ImageView(tableView.getContext());
                headerIcon.setImageDrawable(measurement.getIcon());
                headerIcon.setLayoutParams(new TableRow.LayoutParams(TableLayout.LayoutParams.MATCH_PARENT, TableLayout.LayoutParams.MATCH_PARENT, 1));
                headerIcon.getLayoutParams().width = 0;
                headerIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                headerIcon.getLayoutParams().height = pxImageDp(20);

                tableHeaderView.addView(headerIcon);
            }
        }

        ArrayList<HashMap<Integer, Spanned>> dataRowList = new ArrayList<>();

        int displayCount = 0;

        for (int i = (maxSize * selectedSubpageNr); i < scaleMeasurementList.size(); i++) {
            ScaleMeasurement scaleMeasurement = scaleMeasurementList.get(i);

            ScaleMeasurement prevScaleMeasurement = null;
            if (i < scaleMeasurementList.size() - 1) {
                prevScaleMeasurement = scaleMeasurementList.get(i + 1);
            }

            HashMap<Integer, Spanned> dataRow = new HashMap<>();

            int columnNr = 0;
            dataRow.put(columnNr++, new SpannedString(Long.toString(scaleMeasurement.getId())));

            for (MeasurementView measurement : measurementsList) {
                measurement.loadFrom(scaleMeasurement, prevScaleMeasurement);

                if (measurement.isVisible()) {
                    SpannableStringBuilder text = new SpannableStringBuilder();
                    text.append(measurement.getValueAsString());
                    text.append("\n");
                    measurement.appendDiffValue(text);

                    dataRow.put(columnNr++, text);
                }
            }

            dataRowList.add(dataRow);

            displayCount++;

            if (maxSize <= displayCount) {
                break;
            }
        }

        tableDataView.setAdapter(new ListViewAdapter(dataRowList));
        tableDataView.setOnItemClickListener(new onClickListenerRow());
    }

    private int pxImageDp(float dp) {
        return (int)(dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private class onClickListenerRow implements AdapterView.OnItemClickListener {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long click_id) {
            LinearLayout dataRow = (LinearLayout)view;
            TextView idTextView = (TextView) dataRow.getChildAt(0);
            int id = Integer.parseInt(idTextView.getText().toString());

            Intent intent = new Intent(tableView.getContext(), DataEntryActivity.class);
            intent.putExtra(DataEntryActivity.EXTRA_ID, id);
            startActivityForResult(intent, 1);        }
    }

    private class onClickListenerImport implements View.OnClickListener {
        @Override
        public void onClick(View v) {

            int selectedUserId = OpenScale.getInstance(getContext()).getSelectedScaleUserId();

            if (selectedUserId == -1)
            {
                AlertDialog.Builder infoDialog = new AlertDialog.Builder(v.getContext());

                infoDialog.setMessage(getResources().getString(R.string.info_no_selected_user));

                infoDialog.setPositiveButton(getResources().getString(R.string.label_ok), null);

                infoDialog.show();
            }
            else
                {
                    AlertDialog.Builder filenameDialog = new AlertDialog.Builder(getActivity());

                    filenameDialog.setTitle(getResources().getString(R.string.info_set_filename) + " /sdcard ...");

                    String exportFilename = prefs.getString("exportFilename", "/openScale_data_" + OpenScale.getInstance(getContext()).getSelectedScaleUser().getUserName() + ".csv");

                    final EditText txtFilename = new EditText(tableView.getContext());
                    txtFilename.setText(exportFilename);

                    filenameDialog.setView(txtFilename);

                    filenameDialog.setPositiveButton(getResources().getString(R.string.label_ok), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            OpenScale.getInstance(getContext()).importData(Environment.getExternalStorageDirectory().getPath() + txtFilename.getText().toString());
                            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(tableView.getContext());
                            prefs.edit().putString("exportFilename", txtFilename.getText().toString()).commit();
                            updateOnView(OpenScale.getInstance(getContext()).getScaleMeasurementList());
                        }
                    });

                    filenameDialog.setNegativeButton(getResources().getString(R.string.label_cancel), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.dismiss();
                        }
                    });


                    filenameDialog.show();
                }
        }
    }

    private class onClickListenerExport implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            AlertDialog.Builder filenameDialog = new AlertDialog.Builder(getActivity());

            filenameDialog.setTitle(getResources().getString(R.string.info_set_filename) + " " + Environment.getExternalStorageDirectory().getPath());

            final ScaleUser selectedScaleUser = OpenScale.getInstance(getContext()).getSelectedScaleUser();
            String exportFilename = prefs.getString("exportFilename" + selectedScaleUser.getId(), "openScale_data_" + selectedScaleUser.getUserName() + ".csv");

            final EditText txtFilename = new EditText(tableView.getContext());
            txtFilename.setText(exportFilename);

            filenameDialog.setView(txtFilename);

            filenameDialog.setPositiveButton(getResources().getString(R.string.label_export), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    String fullPath = Environment.getExternalStorageDirectory().getPath() + "/" + txtFilename.getText().toString();

                    if (OpenScale.getInstance(getContext()).exportData(fullPath)) {
                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(tableView.getContext());
                        prefs.edit().putString("exportFilename" + selectedScaleUser.getId(), txtFilename.getText().toString()).commit();
                        Toast.makeText(getContext(), getResources().getString(R.string.info_data_exported) + " " + fullPath, Toast.LENGTH_SHORT).show();
                    }
                }
            });

            filenameDialog.setNeutralButton(getResources().getString(R.string.label_share), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    String fullPath = Environment.getExternalStorageDirectory().getPath() + "/tmp/" + txtFilename.getText().toString();

                    if (!OpenScale.getInstance(getContext()).exportData(fullPath)) {
                        return;
                    }

                    Intent intentShareFile = new Intent(Intent.ACTION_SEND);
                    File shareFile = new File(fullPath);

                    if(shareFile.exists()) {
                        intentShareFile.setType("text/comma-separated-values");
                        intentShareFile.putExtra(Intent.EXTRA_STREAM, Uri.parse("file://"+fullPath));

                        intentShareFile.putExtra(Intent.EXTRA_SUBJECT, "openScale export csv file");
                        intentShareFile.putExtra(Intent.EXTRA_TEXT, txtFilename.getText().toString());

                        startActivity(Intent.createChooser(intentShareFile, getResources().getString(R.string.label_share)));
                    }
                }
            });


            filenameDialog.show();
        }
    }

    private class onClickListenerMoveSubpageLeft implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (selectedSubpageNr > 0) {
                selectedSubpageNr--;
                updateOnView(OpenScale.getInstance(getContext()).getScaleMeasurementList());
            }
        }
    }

    private class onClickListenerMoveSubpageRight implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (selectedSubpageNr < (subpageView.getChildCount() - 3)) {
                selectedSubpageNr++;
                updateOnView(OpenScale.getInstance(getContext()).getScaleMeasurementList());
            }
        }
    }

    private class onClickListenerSubpageSelect implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            TextView nrView = (TextView)v;

            selectedSubpageNr = Integer.parseInt(nrView.getText().toString())-1;
            updateOnView(OpenScale.getInstance(getContext()).getScaleMeasurementList());
        }
    }

    private class ListViewAdapter extends BaseAdapter {

        private ArrayList<HashMap<Integer, Spanned>> dataList;
        private LinearLayout row;

        public ListViewAdapter(ArrayList<HashMap<Integer, Spanned>> list) {
            super();
            this.dataList = list;
        }

        @Override
        public int getCount() {
            return dataList.size();
        }

        @Override
        public Object getItem(int position) {
            return dataList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (dataList.isEmpty()) {
                return convertView;
            }

            if (convertView == null) {
                row = new LinearLayout(getContext());
                convertView = row;

                for (int i = 0; i< dataList.get(0).size(); i++) {
                    TextView column = new TextView(getContext());
                    column.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                    column.getLayoutParams().width = 0;
                    column.setGravity(Gravity.CENTER);

                    if ((getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) != Configuration.SCREENLAYOUT_SIZE_XLARGE &&
                       (getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) != Configuration.SCREENLAYOUT_SIZE_LARGE) {
                        column.setTextSize(COMPLEX_UNIT_DIP, 9);
                    }

                    if (i == 0) {
                        column.setVisibility(View.GONE);
                    }

                    row.addView(column);
                }
            }

            LinearLayout convView = (LinearLayout)convertView;

            HashMap<Integer, Spanned> map = dataList.get(position);

            for (int i = 0; i < map.size(); i++) {
                TextView column = (TextView)convView.getChildAt(i);
                column.setText(map.get(i));
            }

            return convertView;
        }

    }
}
