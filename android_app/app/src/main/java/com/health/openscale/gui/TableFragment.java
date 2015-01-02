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

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;

public class TableFragment extends Fragment implements FragmentUpdateListener {
	private View tableView;
	private TableLayout tableDataView;
	
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
            Button btnDeleteAll = (Button)tableView.findViewById(R.id.btnDeleteAll);
            btnDeleteAll.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        }

		return tableView;
	}
	
	@Override
	public void updateOnView(ArrayList<ScaleData> scaleDBEntries)
	{
		TableRow headerRow = (TableRow) tableView.findViewById(R.id.tableHeader);
		tableDataView.removeAllViews();
		tableDataView.addView(headerRow);
		
		for(ScaleData scaleEntry: scaleDBEntries)
		{
			TableRow dataRow = new TableRow(tableView.getContext());
			dataRow.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

            TextView timeIdView = new TextView(tableView.getContext());
            timeIdView.setText(Long.toString(scaleEntry.id));
            timeIdView.setVisibility(View.GONE);
            dataRow.addView(timeIdView);

			TextView dateTextView = new TextView(tableView.getContext());
            if ((getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) == Configuration.SCREENLAYOUT_SIZE_XLARGE ||
                (getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) == Configuration.SCREENLAYOUT_SIZE_LARGE) {
                dateTextView.setText(new SimpleDateFormat("dd. MMM yyyy (EE)").format(scaleEntry.date_time));
            } else{
                dateTextView.setText(new SimpleDateFormat("dd/MM/yy").format(scaleEntry.date_time));
            }
			dateTextView.setPadding(0, 5, 5, 5);
			dataRow.addView(dateTextView);
			
			TextView timeTextView = new TextView(tableView.getContext());
			timeTextView.setText(new SimpleDateFormat("HH:mm").format(scaleEntry.date_time));
			timeTextView.setPadding(0, 5, 5, 5);
			dataRow.addView(timeTextView);
			
			TextView weightView = new TextView(tableView.getContext());
			weightView.setText(Float.toString(scaleEntry.weight));
			weightView.setPadding(0, 5, 5, 5);
			dataRow.addView(weightView);
			
			TextView fatView = new TextView(tableView.getContext());
			fatView.setText(Float.toString(scaleEntry.fat));
			fatView.setPadding(0, 5, 5, 5);
			dataRow.addView(fatView);
			
			TextView waterView = new TextView(tableView.getContext());
			waterView.setText(Float.toString(scaleEntry.water));
			waterView.setPadding(0, 5, 5, 5);
			dataRow.addView(waterView);
			
			TextView muscleView = new TextView(tableView.getContext());
			muscleView.setText(Float.toString(scaleEntry.muscle));
			muscleView.setPadding(0, 5, 5, 5);
			dataRow.addView(muscleView);

            Button deleteButton = new Button(tableView.getContext());
            deleteButton.setText("X");
            deleteButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
            deleteButton.setTextColor(Color.WHITE);
            deleteButton.setBackground(getResources().getDrawable(R.drawable.flat_selector));
            deleteButton.setGravity(Gravity.CENTER);
            deleteButton.setPadding(0, 0, 0, 0);
            deleteButton.setMinimumHeight(35);
            deleteButton.setHeight(35);
            deleteButton.setOnClickListener(new onClickListenerDelete());
            dataRow.addView(deleteButton);


            if ((getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) != Configuration.SCREENLAYOUT_SIZE_XLARGE &&
                (getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) != Configuration.SCREENLAYOUT_SIZE_LARGE)
            {
                dateTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
                timeTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
                weightView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
                fatView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
                waterView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
                muscleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            }

			tableDataView.addView(dataRow, new TableLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
		}
	}

    private class onClickListenerImport implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            AlertDialog.Builder filenameDialog = new AlertDialog.Builder(getActivity());

            filenameDialog.setTitle(getResources().getString(R.string.info_set_filename) + " /sdcard ...");

            final EditText txtFilename = new EditText(tableView.getContext());
            txtFilename.setText("/openScale_data.csv");

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
                        updateOnView(OpenScale.getInstance(tableView.getContext()).getScaleDBEntries());
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

    private class onClickListenerExport implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            AlertDialog.Builder filenameDialog = new AlertDialog.Builder(getActivity());

            filenameDialog.setTitle(getResources().getString(R.string.info_set_filename) + " /sdcard ...");

            final EditText txtFilename = new EditText(tableView.getContext());
            txtFilename.setText("/openScale_data.csv");

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
                    OpenScale.getInstance(tableView.getContext()).deleteAllDBEntries();

                    Toast.makeText(tableView.getContext(), getResources().getString(R.string.info_data_all_deleted), Toast.LENGTH_SHORT).show();
                    updateOnView(OpenScale.getInstance(tableView.getContext()).getScaleDBEntries());
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
            updateOnView(OpenScale.getInstance(tableView.getContext()).getScaleDBEntries());
        }
    }
}
