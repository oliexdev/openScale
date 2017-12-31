/* Copyright (C) 2018  olie.xdev <olie.xdev@googlemail.com>
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

package com.health.openscale.core.database;

import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Delete;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.Query;
import android.arch.persistence.room.Update;

import com.health.openscale.core.datatypes.ScaleData;

import java.util.Date;
import java.util.List;

@Dao
public interface ScaleMeasurementDAO {
    @Query("SELECT * FROM scaledata WHERE datetime = :datetime AND userId = :userId")
    ScaleData get(Date datetime, int userId);

    @Query("SELECT * FROM scaledata WHERE userId = :userId")
    List<ScaleData> getAll(int userId);

    @Query("SELECT * FROM scaledata WHERE id IS :id")
    ScaleData loadById(int id);

    @Insert
    void insert(ScaleData measurement);

    @Insert
    void insertAll(ScaleData... measurements);

    @Update
    void update(ScaleData measurement);

    @Delete
    void delete(ScaleData measurement);
}
