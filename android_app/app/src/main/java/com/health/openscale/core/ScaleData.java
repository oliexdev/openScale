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

package com.health.openscale.core;

import java.util.Date;

public class ScaleData {
	public long id;
    public int user_id;
	public Date date_time;
	public float weight;
	public float fat;
	public float water;
	public float muscle;
    public float waist;
    public float hip;
    public String comment;

    public ScaleData()
    {
        id = -1;
        user_id = -1;
        date_time = new Date();
        weight = 0.0f;
        fat = 0.0f;
        water = 0.0f;
        muscle = 0.0f;
        waist = 0.0f;
        hip = 0.0f;
        comment = new String();
    }

	@Override
	public String toString()
	{
		return "ID : " + id + " USER_ID: " + user_id + " DATE_TIME: " + date_time.toString() + " WEIGHT: " + weight + " FAT: " + fat + " WATER: " + water + " MUSCLE: " + muscle + " WAIST: " + waist + " HIP: " + hip + " COMMENT: " + comment;
	}
}
