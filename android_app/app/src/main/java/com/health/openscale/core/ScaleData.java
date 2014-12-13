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

import android.util.Log;

public class ScaleData {
	public long id;
	public Date date_time;
	public float weight;
	public float fat;
	public float water;
	public float muscle;
	
	@Override
	public String toString()
	{
		return "ID : " + id + " DATE_TIME: " + date_time.toString() + " WEIGHT: " + weight + " FAT: " + fat + " WATER: " + water + " MUSCLE: " + muscle;
	}
}
