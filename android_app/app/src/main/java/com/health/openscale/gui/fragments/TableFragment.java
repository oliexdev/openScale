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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.text.Html;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleData;
import com.health.openscale.gui.activities.DataEntryActivity;
import com.health.openscale.gui.views.BMIMeasurementView;
import com.health.openscale.gui.views.CommentMeasurementView;
import com.health.openscale.gui.views.DateMeasurementView;
import com.health.openscale.gui.views.FatMeasurementView;
import com.health.openscale.gui.views.HipMeasurementView;
import com.health.openscale.gui.views.MeasurementView;
import com.health.openscale.gui.views.MuscleMeasurementView;
import com.health.openscale.gui.views.TimeMeasurementView;
import com.health.openscale.gui.views.WHRMeasurementView;
import com.health.openscale.gui.views.WHtRMeasurementView;
import com.health.openscale.gui.views.WaistMeasurementView;
import com.health.openscale.gui.views.WaterMeasurementView;
import com.health.openscale.gui.views.WeightMeasurementView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.ListIterator;

import static android.util.TypedValue.COMPLEX_UNIT_DIP;

public class TableFragment extends Fragment implements FragmentUpdateListener {
	private View tableView;
	private TableLayout tableDataView;
    private SharedPreferences prefs;

    private ArrayList <MeasurementView> measurementsList;

	public TableFragment() {
		
	}
	
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
	{
		tableView = inflater.inflate(R.layout.fragment_table, container, false);
		
		tableDataView = (TableLayout) tableView.findViewById(R.id.tableDataView);

		tableView.findViewById(R.id.btnImportData).setOnClickListener(new onClickListenerImport());
		tableView.findViewById(R.id.btnExportData).setOnClickListener(new onClickListenerExport());

        measurementsList = new ArrayList<>();

        measurementsList.add(new DateMeasurementView(tableView.getContext()));
        measurementsList.add(new TimeMeasurementView(tableView.getContext()));
        measurementsList.add(new WeightMeasurementView(tableView.getContext()));
        measurementsList.add(new BMIMeasurementView(tableView.getContext()));
        measurementsList.add(new WaterMeasurementView(tableView.getContext()));
        measurementsList.add(new MuscleMeasurementView(tableView.getContext()));
        measurementsList.add(new FatMeasurementView(tableView.getContext()));
        measurementsList.add(new WaistMeasurementView(tableView.getContext()));
        measurementsList.add(new WHtRMeasurementView(tableView.getContext()));
        measurementsList.add(new HipMeasurementView(tableView.getContext()));
        measurementsList.add(new WHRMeasurementView(tableView.getContext()));
        measurementsList.add(new CommentMeasurementView(tableView.getContext()));

        for (MeasurementView measurement : measurementsList) {
            measurement.setEditMode(MeasurementView.MeasurementViewMode.EDIT);
        }

        prefs = PreferenceManager.getDefaultSharedPreferences(tableView.getContext());

        OpenScale.getInstance(getContext()).registerFragment(this);

		return tableView;
	}
	
	@Override
	public void updateOnView(ArrayList<ScaleData> scaleDataList)
	{
        tableDataView.removeAllViews();

        TableRow tableHeader = new TableRow(tableView.getContext());
        tableHeader.setPadding(0, 10, 0, 10);

        // inside dummy id in table header to have the same amount of table columns for header and data
        TextView idView = new TextView(tableView.getContext());
            idView.setVisibility(View.GONE);
        tableHeader.addView(idView);

        for (MeasurementView measurement : measurementsList) {
            measurement.updatePreferences(prefs);

            if (measurement.isVisible()) {
                ImageView headerIcon = new ImageView(tableView.getContext());
                headerIcon.setImageDrawable(measurement.getIcon());
                headerIcon.setLayoutParams(new TableRow.LayoutParams(TableLayout.LayoutParams.WRAP_CONTENT, TableLayout.LayoutParams.WRAP_CONTENT));
                headerIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                headerIcon.getLayoutParams().height = pxImageDp(20);

                tableHeader.addView(headerIcon);
            }
        }

        Button deleteAll = new Button(tableView.getContext());
            deleteAll.setOnClickListener(new onClickListenerDeleteAll());
            deleteAll.setText(tableView.getContext().getResources().getString(R.string.label_delete_all));
            deleteAll.setBackground(ContextCompat.getDrawable(tableView.getContext(), R.drawable.flat_selector));
            deleteAll.setLayoutParams(new TableRow.LayoutParams(TableLayout.LayoutParams.WRAP_CONTENT, TableLayout.LayoutParams.WRAP_CONTENT));
            deleteAll.getLayoutParams().height = pxImageDp(24);
            deleteAll.setTextColor(Color.WHITE);
            deleteAll.setPadding(0,0,0,0);
            deleteAll.setTextSize(COMPLEX_UNIT_DIP, 8);
        tableHeader.addView(deleteAll);

        tableDataView.addView(tableHeader);

        ListIterator<ScaleData> scaleDataItr = scaleDataList.listIterator();

        while (scaleDataItr.hasNext()) {
            ScaleData scaleData = scaleDataItr.next();
            ScaleData prevScaleData;

            if (scaleDataItr.hasNext()) {
                prevScaleData = scaleDataItr.next();
                scaleDataItr.previous();
            } else {
                prevScaleData = new ScaleData();
            }

            TableRow dataRow = new TableRow(tableView.getContext());

            idView = new TextView(tableView.getContext());
            idView.setVisibility(View.GONE);
            idView.setText(Long.toString(scaleData.getId()));
            dataRow.addView(idView);

            for (MeasurementView measurement : measurementsList) {
               measurement.updateValue(scaleData);
               measurement.updateDiff(scaleData, prevScaleData);

                if (measurement.isVisible()) {
                    TextView measurementView = new TextView(tableView.getContext());
                    measurementView.setGravity(Gravity.CENTER);

                    measurementView.setText(Html.fromHtml(measurement.getValueAsString() + "<br>" + measurement.getDiffValue()));

                    if ((getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) != Configuration.SCREENLAYOUT_SIZE_XLARGE &&
                       (getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) != Configuration.SCREENLAYOUT_SIZE_LARGE) {
                        measurementView.setTextSize(COMPLEX_UNIT_DIP, 10);
                    }

                    dataRow.addView(measurementView);
                }
            }

            ImageView deleteImageView = new ImageView(tableView.getContext());
                deleteImageView.setImageDrawable(ContextCompat.getDrawable(tableView.getContext(), R.drawable.delete));
                deleteImageView.setLayoutParams(new TableRow.LayoutParams(TableLayout.LayoutParams.MATCH_PARENT, TableLayout.LayoutParams.MATCH_PARENT));
                deleteImageView.getLayoutParams().height = pxImageDp(16);
                deleteImageView.setOnClickListener(new onClickListenerDelete());
            dataRow.addView(deleteImageView);

            dataRow.setOnClickListener(new onClickListenerRow());
            dataRow.setLayoutParams(new TableLayout.LayoutParams(TableLayout.LayoutParams.WRAP_CONTENT, TableLayout.LayoutParams.WRAP_CONTENT));
            dataRow.setPadding(0,0,0,10);

            // doesn't work if we use a horizontal scroll view
            /*for (int i=1; i<dataRow.getChildCount()-1; i++) {
                tableDataView.setColumnStretchable(i, true);
            }*/

            tableDataView.addView(dataRow);
        }
	}

    private int pxImageDp(float dp) {
        return (int)(dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private class onClickListenerRow implements View.OnClickListener {

        @Override
        public void onClick(View v) {
            TableRow dataRow = (TableRow)v;
            TextView idTextView = (TextView) dataRow.getChildAt(0);
            long id = Long.parseLong(idTextView.getText().toString());

            Intent intent = new Intent(tableView.getContext(), DataEntryActivity.class);
            intent.putExtra("id", id);
            startActivityForResult(intent, 1);
        }
    }

    private class onClickListenerImport implements View.OnClickListener {
        @Override
        public void onClick(View v) {

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(v.getContext());
            int selectedUserId  = prefs.getInt("selectedUserId", -1);

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

                    final EditText txtFilename = new EditText(tableView.getContext());
                    txtFilename.setText("/openScale_data_" + OpenScale.getInstance(getContext()).getSelectedScaleUser().user_name + ".csv");

                    filenameDialog.setView(txtFilename);

                    filenameDialog.setPositiveButton(getResources().getString(R.string.label_ok), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            boolean isError = false;

                            try {
                                OpenScale.getInstance(getContext()).importData(Environment.getExternalStorageDirectory().getPath() + txtFilename.getText().toString());
                            } catch (IOException e) {
                                Toast.makeText(tableView.getContext(), getResources().getString(R.string.error_importing) + " " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                isError = true;
                            }

                            if (!isError) {
                                Toast.makeText(tableView.getContext(), getResources().getString(R.string.info_data_imported) + " /sdcard" + txtFilename.getText().toString(), Toast.LENGTH_SHORT).show();
                                updateOnView(OpenScale.getInstance(getContext()).getScaleDataList());
                            }
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

            filenameDialog.setTitle(getResources().getString(R.string.info_set_filename) + " /sdcard ...");

            final EditText txtFilename = new EditText(tableView.getContext());
            txtFilename.setText("/openScale_data_" + OpenScale.getInstance(getContext()).getSelectedScaleUser().user_name + ".csv");

            filenameDialog.setView(txtFilename);

            filenameDialog.setPositiveButton(getResources().getString(R.string.label_ok), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    boolean isError = false;

                    try {
                        OpenScale.getInstance(getContext()).exportData(Environment.getExternalStorageDirectory().getPath() + txtFilename.getText().toString());
                    } catch (IOException e) {
                        Toast.makeText(tableView.getContext(), getResources().getString(R.string.error_exporting) + " " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        isError = true;
                    }

                    if (!isError) {
                        Toast.makeText(tableView.getContext(), getResources().getString(R.string.info_data_exported) + " /sdcard" + txtFilename.getText().toString(), Toast.LENGTH_SHORT).show();
                    }
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

    private class onClickListenerDeleteAll implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            AlertDialog.Builder deleteAllDialog = new AlertDialog.Builder(getActivity());

            deleteAllDialog.setMessage(getResources().getString(R.string.question_really_delete_all));

            deleteAllDialog.setPositiveButton(getResources().getString(R.string.label_yes), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(tableView.getContext());
                    int selectedUserId  = prefs.getInt("selectedUserId", -1);

                    OpenScale.getInstance(getContext()).clearScaleData(selectedUserId);

                    Toast.makeText(tableView.getContext(), getResources().getString(R.string.info_data_all_deleted), Toast.LENGTH_SHORT).show();
                    updateOnView(OpenScale.getInstance(getContext()).getScaleDataList());
                }
            });

            deleteAllDialog.setNegativeButton(getResources().getString(R.string.label_no), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.dismiss();
                }
            });

            deleteAllDialog.show();
        }
    }

    private class onClickListenerDelete implements View.OnClickListener {
        private long row_id;

        @Override
        public void onClick(View v) {
            TableRow dataRow = (TableRow)v.getParent();
            TextView idTextView = (TextView) dataRow.getChildAt(0);
            row_id = Long.parseLong(idTextView.getText().toString());

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(v.getContext());
            boolean deleteConfirmationEnable  = prefs.getBoolean("deleteConfirmationEnable", true);

            if (deleteConfirmationEnable) {
                AlertDialog.Builder deleteAllDialog = new AlertDialog.Builder(getActivity());
                deleteAllDialog.setMessage(getResources().getString(R.string.question_really_delete));

                deleteAllDialog.setPositiveButton(getResources().getString(R.string.label_yes), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        deleteMeasurement();
                    }
                });

                deleteAllDialog.setNegativeButton(getResources().getString(R.string.label_no), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.dismiss();
                    }
                });

                deleteAllDialog.show();
            }
            else {
                deleteMeasurement();
            }
        }

        public void deleteMeasurement() {
            OpenScale.getInstance(getContext()).deleteScaleData(row_id);

            Toast.makeText(tableView.getContext(), getResources().getString(R.string.info_data_deleted), Toast.LENGTH_SHORT).show();
            updateOnView(OpenScale.getInstance(getContext()).getScaleDataList());
        }
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if(isVisibleToUser) {
            Activity a = getActivity();
            if(a != null) a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        }
    }
}
