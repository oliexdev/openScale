/* Copyright (C) 2018 Erik Johansson <erik@ejohansson.se>
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

import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.utils.CsvHelper;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import static junit.framework.Assert.assertEquals;

public class CsvHelperTest {
    private static final String HEADERS =
            "\"bone\",\"comment\",\"dateTime\",\"fat\",\"hip\",\"lbm\","
            + "\"muscle\",\"waist\",\"water\",\"weight\"\n";

    private void validateEntry(ScaleMeasurement m, int version) throws Exception {
        assertEquals(8.0, m.getWeight(), 0.001);
        assertEquals(2.0, m.getFat(), 0.001);
        assertEquals(7.0, m.getWater(), 0.001);

        if (version > 0) {
            assertEquals(5.0, m.getMuscle(), 0.001);
        }
        else {
            assertEquals(0.0, m.getMuscle(), 0.001);

        }
        if (version > 1) {
            assertEquals(6.0, m.getWaist(), 0.001);
            assertEquals(3.0, m.getHip(), 0.001);
        }
        else {
            assertEquals(0.0, m.getWaist(), 0.001);
            assertEquals(0.0, m.getHip(), 0.001);
        }
        if (version > 2) {
            assertEquals(1.0, m.getBone(), 0.001);
        }
        else {
            assertEquals(0.0, m.getBone(), 0.001);
        }
        if (version > 3) {
            assertEquals(4.0, m.getLbm(), 0.001);
        }
        else {
            assertEquals(0.0, m.getLbm(), 0.001);
        }

        assertEquals("some text", m.getComment());

        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(2018, 2, 1, 12, 45);
        assertEquals(cal.getTime(), m.getDateTime());
    }

    @Test
    public void newStyleSingleEntry() throws Exception {
        final String data = HEADERS
                + "1.0,\"some text\",\"01.03.2018 12:45\",2.0,3.0,4.0,5.0,6.0,7.0,8.0\n";

        List<ScaleMeasurement> list = CsvHelper.importFrom(
                new BufferedReader(new StringReader(data)));

        assertEquals(1, list.size());
        validateEntry(list.get(0), 5);
    }

    @Test
    public void newStyleOldLbwHeaderNameSingleEntry() throws Exception {
        final String data = HEADERS.replace("lbm", "lbw")
                + "1.0,\"some text\",\"01.03.2018 12:45\",2.0,3.0,4.0,5.0,6.0,7.0,8.0\n";

        List<ScaleMeasurement> list = CsvHelper.importFrom(
                new BufferedReader(new StringReader(data)));

        assertEquals(1, list.size());
        validateEntry(list.get(0), 5);
    }

    @Test
    public void exportImport() throws Exception {
        ScaleMeasurement m = new ScaleMeasurement();
        m.setWeight(8.0f);
        m.setFat(2.0f);
        m.setWater(7.0f);
        m.setMuscle(5.0f);
        m.setLbm(4.0f);
        m.setBone(1.0f);
        m.setWaist(6.0f);
        m.setHip(3.0f);
        m.setComment("some text");

        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(2018, 2, 1, 12, 45);
        m.setDateTime(cal.getTime());

        List<ScaleMeasurement> measurements = new ArrayList<>();
        for (int i = 0; i < 10; ++i) {
            measurements.add(m);
        }

        StringWriter writer = new StringWriter();
        CsvHelper.exportTo(writer, measurements);

        List<ScaleMeasurement> imported = CsvHelper.importFrom(
                new BufferedReader(new StringReader(writer.toString())));
        assertEquals(measurements.size(), imported.size());
        for (ScaleMeasurement newM : imported) {
            validateEntry(newM, 5);
        }
    }

    @Test
    public void oldVersion16SingleEntry() throws Exception {
        final String data =
                "01.03.2018 12:45,8.0,2.0,7.0,5.0,4.0,1.0,6.0,3.0,some text\n";

        List<ScaleMeasurement> list = CsvHelper.importFrom(
                new BufferedReader(new StringReader(data)));

        assertEquals(1, list.size());
        validateEntry(list.get(0), 4);
    }

    @Test
    public void oldVersion155SingleEntry() throws Exception {
        final String data =
                "01.03.2018 12:45,8.0,2.0,7.0,5.0,1.0,6.0,3.0,some text\n";

        List<ScaleMeasurement> list = CsvHelper.importFrom(
                new BufferedReader(new StringReader(data)));

        assertEquals(1, list.size());
        validateEntry(list.get(0), 3);
    }

    @Test
    public void oldVersion13SingleEntry() throws Exception {
        final String data =
                "01.03.2018 12:45,8.0,2.0,7.0,5.0,6.0,3.0,some text\n";

        List<ScaleMeasurement> list = CsvHelper.importFrom(
                new BufferedReader(new StringReader(data)));

        assertEquals(1, list.size());
        validateEntry(list.get(0), 2);
    }

    @Test
    public void oldVersion12SingleEntry() throws Exception {
        final String data =
                "01.03.2018 12:45,8.0,2.0,7.0,5.0,some text\n";

        List<ScaleMeasurement> list = CsvHelper.importFrom(
                new BufferedReader(new StringReader(data)));

        assertEquals(1, list.size());
        validateEntry(list.get(0), 1);
    }

    @Test
    public void oldVersion10SingleEntry() throws Exception {
        final String data =
                "01.03.2018 12:45,8.0,2.0,7.0,some text\n";

        List<ScaleMeasurement> list = CsvHelper.importFrom(
                new BufferedReader(new StringReader(data)));

        assertEquals(1, list.size());
        validateEntry(list.get(0), 0);
    }

    @Test(expected = ParseException.class)
    public void empty() throws Exception {
        final String data = "";
        CsvHelper.importFrom(new BufferedReader(new StringReader(data)));
    }
}
