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

package com.health.openscale.core.datatypes;

import java.util.Date;

public class ScaleUser {
    public static final String[] UNIT_STRING = new String[] {"kg", "lb", "st"};

	public int id;
    public String user_name;
	public Date birthday;
	public int body_height;
	public int scale_unit;
    public int gender;
    public float initial_weight;
    public float goal_weight;
    public Date goal_date;

    public ScaleUser() {
        id = -1;
        user_name = new String();
        birthday = new Date();
        body_height = -1;
        scale_unit = 0;
        gender = 0;
        initial_weight = -1;
        goal_weight = -1;
        goal_date = new Date();
    }

    public boolean isMale()
    {
        if (gender == 0)
            return true;

        return false;
    }

	@Override
	public String toString()
	{
		return "ID : " + id + " NAME: " + user_name + " BIRTHDAY: " + birthday.toString() + " BODY_HEIGHT: " + body_height + " SCALE_UNIT: " + UNIT_STRING[scale_unit] + " GENDER " + gender + " INITIAL WEIGHT " + initial_weight + " GOAL WEIGHT " + goal_weight + " GOAL DATE " + goal_date.toString();
	}
}
