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
import android.database.Cursor;

import com.health.openscale.core.datatypes.ScaleUser;

import java.util.List;

@Dao
public interface ScaleUserDAO {
    @Query("SELECT * FROM scaleUsers")
    List<ScaleUser> getAll();

    @Query("SELECT * FROM scaleUsers WHERE id = :id")
    ScaleUser get(int id);

    @Insert
    long insert(ScaleUser user);

    @Insert
    void insertAll(List<ScaleUser> userList);

    @Update
    void update(ScaleUser user);

    @Delete
    void delete(ScaleUser user);

    // selectAll() and select() are equivalent to getall() and get(), but return a Cursor,
    // for exposing via a ContentProvider.
    @Query("SELECT * FROM scaleUsers")
    Cursor selectAll();

    @Query("SELECT * FROM scaleUsers WHERE id = :id")
    Cursor select(int id);

}
