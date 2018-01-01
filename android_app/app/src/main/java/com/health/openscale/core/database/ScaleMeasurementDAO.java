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
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.Query;
import android.arch.persistence.room.Update;

import com.health.openscale.core.datatypes.ScaleData;

import java.util.Date;
import java.util.List;

@Dao
public interface ScaleMeasurementDAO {
    @Query("SELECT * FROM scaledata WHERE datetime = :datetime AND userId = :userId AND enabled = 1")
    ScaleData get(Date datetime, int userId);

    @Query("SELECT * FROM scaledata WHERE id = :id AND enabled = 1")
    ScaleData get(int id);

    @Query("SELECT * FROM scaledata WHERE datetime < (SELECT datetime FROM scaledata WHERE id = :id) AND userId = :userId AND enabled = 1 ORDER BY datetime DESC LIMIT 0,1")
    ScaleData getPrevious(int id, int userId);

    @Query("SELECT * FROM scaledata WHERE datetime > (SELECT datetime FROM scaledata WHERE id = :id) AND userId = :userId AND enabled = 1 LIMIT 0,1")
    ScaleData getNext(int id, int userId);

    @Query("SELECT * FROM scaledata WHERE userId = :userId AND enabled = 1 ORDER BY datetime DESC")
    List<ScaleData> getAll(int userId);

    @Query("SELECT * FROM scaledata WHERE datetime >= :startYear AND datetime < :endYear AND userId = :userId AND enabled = 1 ORDER BY datetime DESC")
    List<ScaleData> getAllInRange(Date startYear, Date endYear, int userId);

    @Insert
    void insert(ScaleData measurement);

    @Insert
    void insertAll(List<ScaleData> measurementList);

    @Update
    void update(ScaleData measurement);

    @Query("UPDATE scaledata SET enabled = 0 WHERE id = :id")
    void delete(int id);

    @Query("DELETE FROM scaledata WHERE userId = :userId")
    void deleteAll(int userId);
}
