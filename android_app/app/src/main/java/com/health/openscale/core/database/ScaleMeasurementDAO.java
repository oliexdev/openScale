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

import android.database.Cursor;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.health.openscale.core.datatypes.ScaleMeasurement;

import java.util.Date;
import java.util.List;

@Dao
public interface ScaleMeasurementDAO {
    @Query("SELECT * FROM scaleMeasurements WHERE datetime = :datetime AND userId = :userId")
    ScaleMeasurement get(Date datetime, int userId);

    @Query("SELECT * FROM scaleMeasurements WHERE id = :id")
    ScaleMeasurement get(int id);

    @Query("SELECT * FROM scaleMeasurements WHERE datetime < (SELECT datetime FROM scaleMeasurements WHERE id = :id) AND userId = :userId AND enabled = 1 ORDER BY datetime DESC LIMIT 0,1")
    ScaleMeasurement getPrevious(int id, int userId);

    @Query("SELECT * FROM scaleMeasurements WHERE datetime > (SELECT datetime FROM scaleMeasurements WHERE id = :id) AND userId = :userId AND enabled = 1 LIMIT 0,1")
    ScaleMeasurement getNext(int id, int userId);

    @Query("SELECT count(id) FROM scaleMeasurements WHERE userId = :userId AND enabled = 1")
    long getCount(int userId);

    @Query("SELECT * FROM scaleMeasurements WHERE userId = :userId AND enabled = 1 ORDER BY datetime DESC")
    List<ScaleMeasurement> getAll(int userId);

    @Query("SELECT * FROM scaleMeasurements WHERE userId = :userId AND enabled = 1 ORDER BY datetime DESC")
    LiveData<List<ScaleMeasurement>> getAllAsLiveData(int userId);

    @Query("SELECT * FROM scaleMeasurements WHERE datetime >= :startYear AND datetime < :endYear AND userId = :userId AND enabled = 1 ORDER BY datetime DESC")
    List<ScaleMeasurement> getAllInRange(Date startYear, Date endYear, int userId);

    @Query("SELECT * FROM scaleMeasurements WHERE userId = :userId AND enabled = 1 ORDER BY datetime DESC LIMIT 1")
    ScaleMeasurement getLatest(int userId);

    @Query("SELECT * FROM scaleMeasurements WHERE userId = :userId AND enabled = 1 ORDER BY datetime ASC LIMIT 1")
    ScaleMeasurement getFirst(int userId);

    @Insert (onConflict = OnConflictStrategy.IGNORE)
    long insert(ScaleMeasurement measurement);

    @Insert (onConflict = OnConflictStrategy.IGNORE)
    void insertAll(List<ScaleMeasurement> measurementList);

    @Update
    void update(ScaleMeasurement measurement);

    @Query("UPDATE scaleMeasurements SET enabled = 0 WHERE id = :id")
    void delete(int id);

    @Query("DELETE FROM scaleMeasurements WHERE userId = :userId")
    void deleteAll(int userId);

    // selectAll() is equivalent to getAll(), but returns a Cursor, for exposing via a ContentProvider.
    @Query("SELECT id as _ID, datetime, weight, fat, water, muscle FROM scaleMeasurements WHERE userId = :userId AND enabled = 1 ORDER BY datetime DESC")
    Cursor selectAll(long userId);
}
