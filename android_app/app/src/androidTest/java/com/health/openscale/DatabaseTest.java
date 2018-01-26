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
package com.health.openscale;

import android.arch.persistence.room.Room;
import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

import com.health.openscale.core.database.AppDatabase;
import com.health.openscale.core.database.ScaleMeasurementDAO;
import com.health.openscale.core.database.ScaleUserDAO;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// run this test as an Android instrumented test!
@RunWith(AndroidJUnit4.class)
public class DatabaseTest {
    private static final double DELTA = 1e-15;

    private AppDatabase appDB;
    private ScaleUserDAO userDao;
    private ScaleMeasurementDAO measurementDAO;

    @Before
    public void initDatabase() {
        Context context = InstrumentationRegistry.getTargetContext();
        appDB = Room.inMemoryDatabaseBuilder(context, AppDatabase.class).build();
        userDao = appDB.userDAO();
        measurementDAO = appDB.measurementDAO();
    }

    @After
    public void closeDatabase() throws IOException {
        appDB.close();
    }

    @Test
    public void userOperations() throws Exception {
        ScaleUser user1 = new ScaleUser();
        ScaleUser user2 = new ScaleUser();

        user1.setUserName("foo");
        user2.setUserName("bar");

        // is user database empty on initialization
        assertTrue(userDao.getAll().isEmpty());

        userDao.insert(user1);

        // was the user successfully inserted
        assertEquals(1, userDao.getAll().size());

        assertEquals("foo", userDao.getAll().get(0).getUserName());

        userDao.insert(user2);
        assertEquals(2, userDao.getAll().size());

        assertEquals("foo", userDao.getAll().get(0).getUserName());
        assertEquals("bar", userDao.getAll().get(1).getUserName());

        // check if get(id) works
        List<ScaleUser> scaleUserList = userDao.getAll();
        ScaleUser firstUser = scaleUserList.get(0);
        ScaleUser secondUser = scaleUserList.get(1);
        assertEquals(firstUser.getUserName(), userDao.get(firstUser.getId()).getUserName());

        // check delete method
        userDao.delete(firstUser);
        assertEquals(1, userDao.getAll().size());
        assertEquals(secondUser.getUserName(), userDao.getAll().get(0).getUserName());

        // check update method
        secondUser.setUserName("foobar");
        userDao.update(secondUser);
        assertEquals("foobar", userDao.get(secondUser.getId()).getUserName());

        // clear database
        userDao.delete(secondUser);
        assertTrue(userDao.getAll().isEmpty());

        // check insert user list
        ScaleUser user3 = new ScaleUser();
        user3.setUserName("bob");

        List<ScaleUser> myScaleUserList = new ArrayList<>();
        myScaleUserList.add(user1);
        myScaleUserList.add(user2);
        myScaleUserList.add(user3);

        userDao.insertAll(myScaleUserList);
        assertEquals(3, userDao.getAll().size());
    }

    @Test
    public void measurementOperations() throws Exception {
        final ScaleUser scaleUser1 = new ScaleUser();
        final ScaleUser scaleUser2 = new ScaleUser();

        scaleUser1.setId((int)userDao.insert(scaleUser1));
        scaleUser2.setId((int)userDao.insert(scaleUser2));

        // User 1 data initialization
        final int user1 = scaleUser1.getId();
        ScaleMeasurement measurement11 = new ScaleMeasurement();
        ScaleMeasurement measurement12 = new ScaleMeasurement();
        ScaleMeasurement measurement13 = new ScaleMeasurement();

        measurement11.setUserId(user1);
        measurement12.setUserId(user1);
        measurement13.setUserId(user1);

        measurement11.setWeight(10.0f);
        measurement12.setWeight(20.0f);
        measurement13.setWeight(30.0f);

        measurement11.setDateTime(new Date(100));
        measurement12.setDateTime(new Date(200));
        measurement13.setDateTime(new Date(300));

        // User 2 data initialization
        final int user2 = scaleUser2.getId();
        ScaleMeasurement measurement21 = new ScaleMeasurement();
        ScaleMeasurement measurement22 = new ScaleMeasurement();

        measurement21.setUserId(user2);
        measurement22.setUserId(user2);

        measurement21.setWeight(15.0f);
        measurement22.setWeight(25.0f);

        measurement21.setDateTime(new Date(150));
        measurement22.setDateTime(new Date(250));

        // check if database is empty
        assertTrue(measurementDAO.getAll(user1).isEmpty());
        assertTrue(measurementDAO.getAll(user2).isEmpty());

        // insert measurement as list and single insertion
        List<ScaleMeasurement> scaleMeasurementList = new ArrayList<>();
        scaleMeasurementList.add(measurement11);
        scaleMeasurementList.add(measurement13);
        scaleMeasurementList.add(measurement12);

        measurementDAO.insertAll(scaleMeasurementList);

        assertEquals(3, measurementDAO.getAll(user1).size());

        measurementDAO.insert(measurement22);
        measurementDAO.insert(measurement21);

        assertEquals(2, measurementDAO.getAll(user2).size());

        // check if sorted DESC by date correctly
        assertEquals(30.0f, measurementDAO.getAll(user1).get(0).getWeight(), DELTA);
        assertEquals(25.0f, measurementDAO.getAll(user2).get(0).getWeight(), DELTA);

        // don't allow insertion with the same date
        long id = measurementDAO.insert(measurement11);
        assertEquals(-1 , id);
        assertEquals(3, measurementDAO.getAll(user1).size());

        // test get(datetime) method
        assertEquals(20.0f, measurementDAO.get(new Date(200), user1).getWeight(), DELTA);

        // test get(id) method
        scaleMeasurementList = measurementDAO.getAll(user1);

        assertEquals(scaleMeasurementList.get(2).getWeight(), measurementDAO.get(scaleMeasurementList.get(2).getId()).getWeight(), DELTA);

        // test getPrevious(id) method
        assertNull(measurementDAO.getPrevious(scaleMeasurementList.get(2).getId(), user1));
        assertEquals(scaleMeasurementList.get(2).getWeight(), measurementDAO.getPrevious(scaleMeasurementList.get(1).getId(), user1).getWeight(), DELTA);
        assertEquals(scaleMeasurementList.get(1).getWeight(), measurementDAO.getPrevious(scaleMeasurementList.get(0).getId(), user1).getWeight(), DELTA);

        // test getNext(id) method
        assertNull(measurementDAO.getNext(scaleMeasurementList.get(0).getId(), user1));
        assertEquals(scaleMeasurementList.get(0).getWeight(), measurementDAO.getNext(scaleMeasurementList.get(1).getId(), user1).getWeight(), DELTA);
        assertEquals(scaleMeasurementList.get(1).getWeight(), measurementDAO.getNext(scaleMeasurementList.get(2).getId(), user1).getWeight(), DELTA);

        // test getAllInRange method
        assertEquals(1, measurementDAO.getAllInRange(new Date(0), new Date(200), user1).size());
        assertEquals(0, measurementDAO.getAllInRange(new Date(0), new Date(50), user1).size());
        assertEquals(2, measurementDAO.getAllInRange(new Date(100), new Date(201), user1).size());
        assertEquals(1, measurementDAO.getAllInRange(new Date(0), new Date(200), user1).size());
        assertEquals(3, measurementDAO.getAllInRange(new Date(0), new Date(1000), user1).size());
        assertEquals(2, measurementDAO.getAllInRange(new Date(150), new Date(400), user1).size());

        assertEquals(0, measurementDAO.getAllInRange(new Date(10), new Date(20), user2).size());
        assertEquals(1, measurementDAO.getAllInRange(new Date(70), new Date(200), user2).size());
        assertEquals(2, measurementDAO.getAllInRange(new Date(0), new Date(1000), user2).size());

        // test update method
        assertEquals(30.0f, measurementDAO.get(scaleMeasurementList.get(0).getId()).getWeight(), DELTA);
        scaleMeasurementList.get(0).setWeight(42.0f);
        measurementDAO.update(scaleMeasurementList.get(0));
        assertEquals(42.0f, measurementDAO.get(scaleMeasurementList.get(0).getId()).getWeight(), DELTA);

        // test delete method
        assertEquals(3, measurementDAO.getAll(user1).size());
        measurementDAO.delete(scaleMeasurementList.get(0).getId());
        assertEquals(2, measurementDAO.getAll(user1).size());

        // test delete all method
        assertEquals(2, measurementDAO.getAll(user1).size());
        assertEquals(2, measurementDAO.getAll(user2).size());
        measurementDAO.deleteAll(user1);
        measurementDAO.deleteAll(user2);
        assertEquals(0, measurementDAO.getAll(user1).size());
        assertEquals(0, measurementDAO.getAll(user2).size());

        assertTrue(measurementDAO.getAll(user1).isEmpty());
        assertTrue(measurementDAO.getAll(user2).isEmpty());
    }
}
