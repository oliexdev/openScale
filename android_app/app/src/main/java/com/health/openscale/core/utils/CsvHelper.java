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

package com.health.openscale.core.utils;

import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.j256.simplecsv.processor.ColumnNameMatcher;
import com.j256.simplecsv.processor.CsvProcessor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.text.ParseException;
import java.util.List;

public class CsvHelper {

    public static void exportTo(Writer writer, List<ScaleMeasurement> measurements) throws IOException {
        CsvProcessor<ScaleMeasurement> csvProcessor = new CsvProcessor<>(ScaleMeasurement.class);
        csvProcessor.writeAll(writer, measurements, true);
    }

    private static String[] getOldStyleHeaders(String sampleLine) {
        if (sampleLine == null) {
            return null;
        }

        final String[] fields = sampleLine.split(",", -1);

        // Return an array with header fields that match the guessed version.
        if (fields.length == 10) {
            // From version 1.6 up to 1.7
            return new String[]{"dateTime", "weight", "fat", "water", "muscle", "lbm",
                    "bone", "waist", "hip", "comment"};
        }
        else if (fields.length == 9) {
            // From version 1.5.5
            return new String[]{"dateTime", "weight", "fat", "water", "muscle", "bone",
                    "waist", "hip", "comment"};
        }
        else if (fields.length == 8) {
            // From version 1.3
            return new String[]{"dateTime", "weight", "fat", "water", "muscle", "waist",
                    "hip", "comment"};
        }
        else if (fields.length == 6) {
            // From version 1.2
            return new String[]{"dateTime", "weight", "fat", "water", "muscle", "comment"};
        }
        else if (fields.length == 5) {
            // From version 1.0
            return new String[]{"dateTime", "weight", "fat", "water", "comment"};
        }

        // Unknown input data format
        return null;
    }

    public static List<ScaleMeasurement> importFrom(BufferedReader reader)
            throws IOException, ParseException {
        CsvProcessor<ScaleMeasurement> csvProcessor =
                new CsvProcessor<>(ScaleMeasurement.class)
                    .withHeaderValidation(true)
                    .withFlexibleOrder(true)
                    .withAlwaysTrimInput(true)
                    .withAllowPartialLines(true);

        csvProcessor.setColumnNameMatcher(new ColumnNameMatcher() {
            @Override
            public boolean matchesColumnName(String definitionName, String csvName) {
                return definitionName.equals(csvName)
                        || (definitionName.equals("lbm") && csvName.equals("lbw"));
            }
        });

        reader.mark(1000);
        try {
            csvProcessor.readHeader(reader, null);
        }
        catch (ParseException ex) {
            // Try to import it as an old style CSV export
            reader.reset();
            final String sampleLine = reader.readLine();
            reader.reset();

            final String[] header = getOldStyleHeaders(sampleLine);

            if (header == null) {
                // Don't know what to do with this, let Simple CSV error out
                return csvProcessor.readAll(reader, null);
            }

            csvProcessor.validateHeaderColumns(header, null);
        }

        return csvProcessor.readRows(reader, null);
    }
}
