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

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Environment;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TableRow.LayoutParams;
import android.widget.TextView;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.ScaleData;

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
		
		tableView.findViewById(R.id.btnImportData).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
            	openImportDialog();
            }
        });
		
		tableView.findViewById(R.id.btnExportData).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
            	openExportDialog();
            }
        });
		
		tableView.findViewById(R.id.btnDeleteAll).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
            	openDeleteAllDialog();
            }
        });
		
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
			dataRow.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
			
			TextView dateTextView = new TextView(tableView.getContext());
			dateTextView.setText(new SimpleDateFormat("dd. MMM yyyy (EE)").format(scaleEntry.date_time));
			dateTextView.setPadding(5, 5, 5, 5);
			dataRow.addView(dateTextView);
			
			TextView timeTextView = new TextView(tableView.getContext());
			timeTextView.setText(new SimpleDateFormat("HH:mm").format(scaleEntry.date_time));
			timeTextView.setPadding(5, 5, 5, 5);
			dataRow.addView(timeTextView);
			
			TextView weightView = new TextView(tableView.getContext());
			weightView.setText(Float.toString(scaleEntry.weight));
			weightView.setPadding(5, 5, 5, 5);
			dataRow.addView(weightView);
			
			TextView fatView = new TextView(tableView.getContext());
			fatView.setText(Float.toString(scaleEntry.fat));
			fatView.setPadding(5, 5, 5, 5);
			dataRow.addView(fatView);
			
			TextView waterView = new TextView(tableView.getContext());
			waterView.setText(Float.toString(scaleEntry.water));
			waterView.setPadding(5, 5, 5, 5);
			dataRow.addView(waterView);
			
			TextView muscleView = new TextView(tableView.getContext());
			muscleView.setText(Float.toString(scaleEntry.muscle));
			muscleView.setPadding(5, 5, 5, 5);
			dataRow.addView(muscleView);
			
			tableDataView.addView(dataRow, new TableLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		}
	}
	
	public void openImportDialog() 
	{
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
	
	public void openExportDialog() 
	{
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
	
	public void openDeleteAllDialog()
	{
    	AlertDialog.Builder deleteAllDialog = new AlertDialog.Builder(getActivity());
    	
    	deleteAllDialog.setMessage(getResources().getString(R.string.question_really_delete_all));
    	
    	deleteAllDialog.setPositiveButton(getResources().getString(R.string.label_yes), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
            	OpenScale.getInstance(tableView.getContext()).deleteAllDBEntries();
            	
            	Toast.makeText(tableView.getContext(), getResources().getString(R.string.info_data_deleted), Toast.LENGTH_SHORT).show();
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
