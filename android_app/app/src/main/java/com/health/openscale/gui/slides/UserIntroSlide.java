/* Copyright (C) 2019  olie.xdev <olie.xdev@googlemail.com>
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
package com.health.openscale.gui.slides;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleUser;

import java.util.List;

public class UserIntroSlide extends Fragment{

    private static final String ARG_LAYOUT_RES_ID = "layoutResId";
    private int layoutResId;
    private Button btnAddUser;
    private TableLayout tblUsers;

    public static UserIntroSlide newInstance(int layoutResId) {
        UserIntroSlide sampleSlide = new UserIntroSlide();

        Bundle args = new Bundle();
        args.putInt(ARG_LAYOUT_RES_ID, layoutResId);
        sampleSlide.setArguments(args);

        return sampleSlide;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getArguments() != null && getArguments().containsKey(ARG_LAYOUT_RES_ID)) {
            layoutResId = getArguments().getInt(ARG_LAYOUT_RES_ID);
        }
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(layoutResId, container, false);

        btnAddUser = view.findViewById(R.id.btnAddUser);
        tblUsers = view.findViewById(R.id.tblUsers);

        btnAddUser.setOnClickListener(new onBtnAddUserClickListener());

        updateTableUsers();

        return view;
    }

    private class onBtnAddUserClickListener implements View.OnClickListener {

        @Override
        public void onClick(View view) {
            Intent intent = new Intent(getContext(), SlideToNavigationAdapter.class);
            intent.putExtra(SlideToNavigationAdapter.EXTRA_MODE, SlideToNavigationAdapter.EXTRA_USER_SETTING_MODE);
            startActivityForResult(intent, 100);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        updateTableUsers();
    }


    private void updateTableUsers() {
        tblUsers.removeAllViews();
        tblUsers.setStretchAllColumns(true);

        List<ScaleUser> scaleUserList = OpenScale.getInstance().getScaleUserList();

        TableRow header = new TableRow(getContext());

        TextView headerUsername = new TextView(getContext());
        headerUsername.setText(R.string.label_user_name);
        headerUsername.setGravity(Gravity.CENTER_HORIZONTAL);
        headerUsername.setTypeface(null, Typeface.BOLD);
        header.addView(headerUsername);

        TextView headAge = new TextView(getContext());
        headAge.setText(R.string.label_age);
        headAge.setGravity(Gravity.CENTER_HORIZONTAL);
        headAge.setTypeface(null, Typeface.BOLD);
        header.addView(headAge);

        TextView headerGender = new TextView(getContext());
        headerGender.setText(R.string.label_gender);
        headerGender.setGravity(Gravity.CENTER_HORIZONTAL);
        headerGender.setTypeface(null, Typeface.BOLD);
        header.addView(headerGender);

        tblUsers.addView(header);

        if (!scaleUserList.isEmpty()) {
            TableRow row = new TableRow(getContext());

            for (ScaleUser scaleUser : scaleUserList) {
                row = new TableRow(getContext());

                TextView txtUsername = new TextView(getContext());
                txtUsername.setText(scaleUser.getUserName());
                txtUsername.setGravity(Gravity.CENTER_HORIZONTAL);
                row.addView(txtUsername);

                TextView txtAge = new TextView(getContext());
                txtAge.setText(Integer.toString(scaleUser.getAge()));
                txtAge.setGravity(Gravity.CENTER_HORIZONTAL);
                row.addView(txtAge);

                TextView txtGender = new TextView(getContext());
                txtGender.setText((scaleUser.getGender().isMale()) ? getString(R.string.label_male) : getString(R.string.label_female));
                txtGender.setGravity(Gravity.CENTER_HORIZONTAL);
                row.addView(txtGender);

                row.setGravity(Gravity.CENTER_HORIZONTAL);

                tblUsers.addView(row);
            }
        } else {
            TableRow row = new TableRow(getContext());

            TextView txtEmpty = new TextView(getContext());
            txtEmpty.setText("[" + getContext().getString(R.string.label_empty) + "]");
            txtEmpty.setGravity(Gravity.CENTER_HORIZONTAL);
            row.addView(txtEmpty);

            row.setGravity(Gravity.CENTER_HORIZONTAL);

            tblUsers.addView(row);
        }
    }
}
