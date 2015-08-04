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
package com.health.openscale.gui;

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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TableRow.LayoutParams;
import android.widget.TextView;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.ScaleData;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;

public class TableFragment extends Fragment implements FragmentUpdateListener {
	private View tableView;
	private TableLayout tableDataView;
    private SharedPreferences prefs;

	public TableFragment() {
		
	}
	
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
	{
		tableView = inflater.inflate(R.layout.fragment_table, container, false);
		
		tableDataView = (TableLayout) tableView.findViewById(R.id.tableDataView);
		
		tableView.findViewById(R.id.btnImportData).setOnClickListener(new onClickListenerImport());
		
		tableView.findViewById(R.id.btnExportData).setOnClickListener(new onClickListenerExport());
		
		tableView.findViewById(R.id.btnDeleteAll).setOnClickListener(new onClickListenerDeleteAll());

        if ((getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) != Configuration.SCREENLAYOUT_SIZE_XLARGE &&
            (getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) != Configuration.SCREENLAYOUT_SIZE_LARGE)
        {
            TextView txtDateTableHeader = (TextView)tableView.findViewById(R.id.txtDateTableHeader);
            txtDateTableHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            TextView txtTimeTableHeader = (TextView)tableView.findViewById(R.id.txtTimeTableHeader);
            txtTimeTableHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            TextView txtWeightTableHeader = (TextView)tableView.findViewById(R.id.txtWeightTableHeader);
            txtWeightTableHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            TextView txtFatTableHeader = (TextView)tableView.findViewById(R.id.txtFatTableHeader);
            txtFatTableHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            TextView txtWaterTableHeader = (TextView)tableView.findViewById(R.id.txtWaterTableHeader);
            txtWaterTableHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            TextView txtMuscleTableHeader = (TextView)tableView.findViewById(R.id.txtMuscleTableHeader);
            txtMuscleTableHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            TextView txtCommentTableHeader = (TextView)tableView.findViewById(R.id.txtCommentTableHeader);
            txtCommentTableHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            Button btnDeleteAll = (Button)tableView.findViewById(R.id.btnDeleteAll);
            btnDeleteAll.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        }

        prefs = PreferenceManager.getDefaultSharedPreferences(tableView.getContext());

        if(!prefs.getBoolean("fatEnable", true)) {
            TextView txtFatTableHeader = (TextView)tableView.findViewById(R.id.txtFatTableHeader);
            txtFatTableHeader.setVisibility(View.GONE);
        }

        if(!prefs.getBoolean("muscleEnable", true)) {
            TextView txtMuscleTableHeader = (TextView)tableView.findViewById(R.id.txtMuscleTableHeader);
            txtMuscleTableHeader.setVisibility(View.GONE);
        }

        if(!prefs.getBoolean("waterEnable", true)) {
            TextView txtWaterTableHeader = (TextView)tableView.findViewById(R.id.txtWaterTableHeader);
            txtWaterTableHeader.setVisibility(View.GONE);
        }

		return tableView;
	}
	
	@Override
	public void updateOnView(ArrayList<ScaleData> scaleDataList)
	{
        tableDataView.setColumnStretchable(1, true);
        tableDataView.setColumnStretchable(2, true);
        tableDataView.setColumnStretchable(3, true);
        if(prefs.getBoolean("fatEnable", true)) {
            tableDataView.setColumnStretchable(4, true);
        }
        if(prefs.getBoolean("waterEnable", true)) {
            tableDataView.setColumnStretchable(5, true);
        }
        if(prefs.getBoolean("muscleEnable", true)) {
            tableDataView.setColumnStretchable(6, true);
        }
        tableDataView.setColumnStretchable(7, true);

		TableRow headerRow = (TableRow) tableView.findViewById(R.id.tableHeader);
		tableDataView.removeAllViews();
		tableDataView.addView(headerRow);

		for(ScaleData scaleData: scaleDataList)
		{
			TableRow dataRow = new TableRow(tableView.getContext());
			dataRow.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

            TextView idView = new TextView(tableView.getContext());
            idView.setText(Long.toString(scaleData.id));
            idView.setVisibility(View.GONE);
            dataRow.addView(idView);

			TextView dateTextView = new TextView(tableView.getContext());
            if ((getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) == Configuration.SCREENLAYOUT_SIZE_XLARGE ||
                (getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) == Configuration.SCREENLAYOUT_SIZE_LARGE) {
                dateTextView.setText(DateFormat.getDateInstance(DateFormat.MEDIUM).format(scaleData.date_time));
            } else{
                dateTextView.setText(DateFormat.getDateInstance(DateFormat.SHORT).format(scaleData.date_time));
            }
			dateTextView.setPadding(0, 5, 5, 5);
			dataRow.addView(dateTextView);
			
			TextView timeTextView = new TextView(tableView.getContext());
			timeTextView.setText(new SimpleDateFormat("HH:mm").format(scaleData.date_time));
			timeTextView.setPadding(0, 5, 5, 5);
			dataRow.addView(timeTextView);
			
			TextView weightView = new TextView(tableView.getContext());
			weightView.setText(Float.toString(scaleData.weight));
			weightView.setPadding(0, 5, 5, 5);
			dataRow.addView(weightView);
			
			TextView fatView = new TextView(tableView.getContext());
			fatView.setText(Float.toString(scaleData.fat));
			fatView.setPadding(0, 5, 5, 5);
            if(!prefs.getBoolean("fatEnable", true)) {
                fatView.setVisibility(View.GONE);
            }
			dataRow.addView(fatView);
			
			TextView waterView = new TextView(tableView.getContext());
			waterView.setText(Float.toString(scaleData.water));
			waterView.setPadding(0, 5, 5, 5);
            if(!prefs.getBoolean("waterEnable", true)) {
                waterView.setVisibility(View.GONE);
            }
            dataRow.addView(waterView);
			
			TextView muscleView = new TextView(tableView.getContext());
			muscleView.setText(Float.toString(scaleData.muscle));
			muscleView.setPadding(0, 5, 5, 5);
            if(!prefs.getBoolean("muscleEnable", true)) {
                muscleView.setVisibility(View.GONE);
            }
            dataRow.addView(muscleView);


            TextView commentView = new TextView(tableView.getContext());
            commentView.setText(scaleData.comment);
            commentView.setPadding(0, 5, 5, 5);
            dataRow.addView(commentView);

            Button deleteButton = new Button(tableView.getContext());
            deleteButton.setText("X");
            deleteButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
            deleteButton.setTextColor(Color.WHITE);
            deleteButton.setBackgroundResource(R.drawable.flat_selector);
            deleteButton.setGravity(Gravity.CENTER);
            deleteButton.setPadding(0, 0, 0, 0);
            deleteButton.setMinimumHeight(35);
            deleteButton.setHeight(35);
            deleteButton.setOnClickListener(new onClickListenerDelete());
            dataRow.addView(deleteButton);

            dataRow.setOnClickListener(new onClickListenerRow());

            if ((getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) != Configuration.SCREENLAYOUT_SIZE_XLARGE &&
                (getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) != Configuration.SCREENLAYOUT_SIZE_LARGE)
            {
                dateTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
                timeTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
                weightView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
                fatView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
                waterView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
                muscleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
                commentView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            }

			tableDataView.addView(dataRow, new TableLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
		}
	}

    private class onClickListenerRow implements View.OnClickListener {

        @Override
        public void onClick(View v) {
            TableRow dataRow = (TableRow)v;
            TextView idTextView = (TextView) dataRow.getChildAt(0);
            long id = Long.parseLong(idTextView.getText().toString());

            Intent intent = new Intent(tableView.getContext(), EditDataActivity.class);
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
                    txtFilename.setText("/openScale_data_" + OpenScale.getInstance(tableView.getContext()).getSelectedScaleUser().user_name + ".csv");

                    filenameDialog.setView(txtFilename);

                    filenameDialog.setPositiveButton(getResources().getString(R.string.label_ok), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            boolean isError = false;

                            try {
                                OpenScale.getInstance(tableView.getContext()).importData(Environment.getExternalStorageDirectory().getPath() + txtFilename.getText().toString());
                            } catch (IOException e) {
                                Toast.makeText(tableView.getContext(), getResources().getString(R.string.error_importing) + " " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                isError = true;
                            }

                            if (!isError) {
                                Toast.makeText(tableView.getContext(), getResources().getString(R.string.info_data_imported) + " /sdcard" + txtFilename.getText().toString(), Toast.LENGTH_SHORT).show();
                                updateOnView(OpenScale.getInstance(tableView.getContext()).getScaleDataList());
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
            txtFilename.setText("/openScale_data_" + OpenScale.getInstance(tableView.getContext()).getSelectedScaleUser().user_name + ".csv");

            filenameDialog.setView(txtFilename);

            filenameDialog.setPositiveButton(getResources().getString(R.string.label_ok), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    boolean isError = false;

                    try {
                        OpenScale.getInstance(tableView.getContext()).exportData(Environment.getExternalStorageDirectory().getPath() + txtFilename.getText().toString());
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

                    OpenScale.getInstance(tableView.getContext()).clearScaleData(selectedUserId);

                    Toast.makeText(tableView.getContext(), getResources().getString(R.string.info_data_all_deleted), Toast.LENGTH_SHORT).show();
                    updateOnView(OpenScale.getInstance(tableView.getContext()).getScaleDataList());
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
        @Override
        public void onClick(View v) {
            TableRow dataRow = (TableRow)v.getParent();
            TextView idTextView = (TextView) dataRow.getChildAt(0);
            long id = Long.parseLong(idTextView.getText().toString());

            OpenScale.getInstance(tableView.getContext()).deleteScaleData(id);

            Toast.makeText(tableView.getContext(), getResources().getString(R.string.info_data_deleted), Toast.LENGTH_SHORT).show();
            updateOnView(OpenScale.getInstance(tableView.getContext()).getScaleDataList());
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
