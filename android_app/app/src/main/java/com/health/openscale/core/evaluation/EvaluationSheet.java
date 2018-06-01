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
package com.health.openscale.core.evaluation;

import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class EvaluationSheet {

    private ScaleUser evalUser;
    private int userAge;

    private List<sheetEntry> fatEvaluateSheet_Man;
    private List<sheetEntry> fatEvaluateSheet_Woman;

    private List<sheetEntry> waterEvaluateSheet_Man;
    private List<sheetEntry> waterEvaluateSheet_Woman;

    private List<sheetEntry> muscleEvaluateSheet_Man;
    private List<sheetEntry> muscleEvaluateSheet_Woman;

    private List<sheetEntry> bmiEvaluateSheet_Man;
    private List<sheetEntry> bmiEvaluateSheet_Woman;

    private List<sheetEntry> waistEvaluateSheet_Man;
    private List<sheetEntry> waistEvaluateSheet_Woman;

    private List<sheetEntry> whrtEvaluateSheet;

    private List<sheetEntry> whrEvaluateSheet_Man;
    private List<sheetEntry> whrEvaluateSheet_Woman;

    private List<sheetEntry> visceralFatEvaluateSheet;

    private class sheetEntry {
        public sheetEntry(int lowAge, int maxAge, float lowLimit, float highLimit)
        {
            this.lowAge = lowAge;
            this.maxAge = maxAge;
            this.lowLimit = lowLimit;
            this.highLimit = highLimit;
        }

        public int lowAge;
        public int maxAge;
        public float lowLimit;
        public float highLimit;
    }


    public EvaluationSheet(ScaleUser user, Date dateTime) {
        evalUser = user;
        userAge = user.getAge(dateTime);

        fatEvaluateSheet_Man = new ArrayList<>();
        fatEvaluateSheet_Woman = new ArrayList<>();

        waterEvaluateSheet_Man = new ArrayList<>();
        waterEvaluateSheet_Woman = new ArrayList<>();

        muscleEvaluateSheet_Man = new ArrayList<>();
        muscleEvaluateSheet_Woman = new ArrayList<>();

        bmiEvaluateSheet_Man = new ArrayList<>();
        bmiEvaluateSheet_Woman = new ArrayList<>();

        waistEvaluateSheet_Man = new ArrayList<>();
        waistEvaluateSheet_Woman = new ArrayList<>();

        whrtEvaluateSheet = new ArrayList<>();

        whrEvaluateSheet_Man = new ArrayList<>();
        whrEvaluateSheet_Woman = new ArrayList<>();

        visceralFatEvaluateSheet = new ArrayList<>();

        initEvaluationSheets();
    }

    private void initEvaluationSheets()
    {
        fatEvaluateSheet_Man.add(new sheetEntry(10, 14, 11, 16));
        fatEvaluateSheet_Man.add(new sheetEntry(15, 19, 12, 17));
        fatEvaluateSheet_Man.add(new sheetEntry(20, 29, 13, 18));
        fatEvaluateSheet_Man.add(new sheetEntry(30, 39, 14, 19));
        fatEvaluateSheet_Man.add(new sheetEntry(40, 49, 15, 20));
        fatEvaluateSheet_Man.add(new sheetEntry(50, 59, 16, 21));
        fatEvaluateSheet_Man.add(new sheetEntry(60, 69, 17, 22));
        fatEvaluateSheet_Man.add(new sheetEntry(70, 1000, 18, 23));


        fatEvaluateSheet_Woman.add(new sheetEntry(10, 14, 16, 21));
        fatEvaluateSheet_Woman.add(new sheetEntry(15, 19, 17, 22));
        fatEvaluateSheet_Woman.add(new sheetEntry(20, 29, 18, 23));
        fatEvaluateSheet_Woman.add(new sheetEntry(30, 39, 19, 24));
        fatEvaluateSheet_Woman.add(new sheetEntry(40, 49, 20, 25));
        fatEvaluateSheet_Woman.add(new sheetEntry(50, 59, 21, 26));
        fatEvaluateSheet_Woman.add(new sheetEntry(60, 69, 22, 27));
        fatEvaluateSheet_Woman.add(new sheetEntry(70, 1000, 23, 28));

        waterEvaluateSheet_Man.add(new sheetEntry(10, 1000, 50, 65));

        waterEvaluateSheet_Woman.add(new sheetEntry(10, 1000, 45, 60));

        muscleEvaluateSheet_Man.add(new sheetEntry(10, 14, 44, 57));
        muscleEvaluateSheet_Man.add(new sheetEntry(15, 19, 43, 56));
        muscleEvaluateSheet_Man.add(new sheetEntry(20, 29, 42, 54));
        muscleEvaluateSheet_Man.add(new sheetEntry(30, 39, 41, 52));
        muscleEvaluateSheet_Man.add(new sheetEntry(40, 49, 40, 50));
        muscleEvaluateSheet_Man.add(new sheetEntry(50, 59, 39, 48));
        muscleEvaluateSheet_Man.add(new sheetEntry(60, 69, 38, 47));
        muscleEvaluateSheet_Man.add(new sheetEntry(70, 1000, 37, 46));


        muscleEvaluateSheet_Woman.add(new sheetEntry(10, 14, 36, 43));
        muscleEvaluateSheet_Woman.add(new sheetEntry(15, 19, 35, 41));
        muscleEvaluateSheet_Woman.add(new sheetEntry(20, 29, 34, 39));
        muscleEvaluateSheet_Woman.add(new sheetEntry(30, 39, 33, 38));
        muscleEvaluateSheet_Woman.add(new sheetEntry(40, 49, 31, 36));
        muscleEvaluateSheet_Woman.add(new sheetEntry(50, 59, 29, 34));
        muscleEvaluateSheet_Woman.add(new sheetEntry(60, 69, 28, 33));
        muscleEvaluateSheet_Woman.add(new sheetEntry(70, 1000, 27, 32));


        bmiEvaluateSheet_Man.add(new sheetEntry(16, 24, 20, 25));
        bmiEvaluateSheet_Man.add(new sheetEntry(25, 34, 21, 26));
        bmiEvaluateSheet_Man.add(new sheetEntry(35, 44, 22, 27));
        bmiEvaluateSheet_Man.add(new sheetEntry(45, 54, 23, 28));
        bmiEvaluateSheet_Man.add(new sheetEntry(55, 64, 24, 29));
        bmiEvaluateSheet_Man.add(new sheetEntry(65, 90, 25, 30));


        bmiEvaluateSheet_Woman.add(new sheetEntry(16, 24, 19, 24));
        bmiEvaluateSheet_Woman.add(new sheetEntry(25, 34, 20, 25));
        bmiEvaluateSheet_Woman.add(new sheetEntry(35, 44, 21, 26));
        bmiEvaluateSheet_Woman.add(new sheetEntry(45, 54, 22, 27));
        bmiEvaluateSheet_Woman.add(new sheetEntry(55, 64, 23, 28));
        bmiEvaluateSheet_Woman.add(new sheetEntry(65, 90, 24, 29));

        waistEvaluateSheet_Man.add(new sheetEntry(18, 90, -1, Converters.fromCentimeter(94, evalUser.getMeasureUnit())));
        waistEvaluateSheet_Woman.add(new sheetEntry(18, 90, -1, Converters.fromCentimeter(80, evalUser.getMeasureUnit())));

        whrtEvaluateSheet.add(new sheetEntry(15, 40, 0.4f, 0.5f));
        whrtEvaluateSheet.add(new sheetEntry(41, 42, 0.4f, 0.51f));
        whrtEvaluateSheet.add(new sheetEntry(43, 44, 0.4f, 0.53f));
        whrtEvaluateSheet.add(new sheetEntry(45, 46, 0.4f, 0.55f));
        whrtEvaluateSheet.add(new sheetEntry(47, 48, 0.4f, 0.57f));
        whrtEvaluateSheet.add(new sheetEntry(49, 50, 0.4f, 0.59f));
        whrtEvaluateSheet.add(new sheetEntry(51, 90, 0.4f, 0.6f));

        whrEvaluateSheet_Man.add(new sheetEntry(18, 90, 0.8f, 0.9f));
        whrEvaluateSheet_Woman.add(new sheetEntry(18, 90, 0.7f, 0.8f));

        visceralFatEvaluateSheet.add(new sheetEntry(18, 90, -1, 12));
    }


    public EvaluationResult evaluateWeight(float weight) {
        float body_height_squared = (evalUser.getBodyHeight() / 100.0f) * (evalUser.getBodyHeight() / 100.0f);
        float lowLimit;
        float highLimit;

        if (evalUser.getGender().isMale()) {
            lowLimit = body_height_squared * 20.0f;
            highLimit = body_height_squared * 25.0f;
        } else {
            lowLimit = body_height_squared * 19.0f;
            highLimit = body_height_squared * 24.0f;
        }

        if (weight < lowLimit) { // low
            return new EvaluationResult(weight,  Converters.fromKilogram(Math.round(lowLimit), evalUser.getScaleUnit()), Converters.fromKilogram(Math.round(highLimit), evalUser.getScaleUnit()), EvaluationResult.EVAL_STATE.LOW);
        } else if (weight >= lowLimit && weight <= highLimit) { // normal
            return new EvaluationResult(weight, Converters.fromKilogram(Math.round(lowLimit), evalUser.getScaleUnit()), Converters.fromKilogram(Math.round(highLimit), evalUser.getScaleUnit()), EvaluationResult.EVAL_STATE.NORMAL);
        } else if (weight > highLimit) { //high
            return new EvaluationResult(weight, Converters.fromKilogram(Math.round(lowLimit), evalUser.getScaleUnit()), Converters.fromKilogram(Math.round(highLimit),  evalUser.getScaleUnit()), EvaluationResult.EVAL_STATE.HIGH);
        }

        return new EvaluationResult(0, -1, -1, EvaluationResult.EVAL_STATE.UNDEFINED);
    }


    public EvaluationResult evaluateBodyFat(float fat) {
        List<sheetEntry> bodyEvaluateSheet;

        if (evalUser.getGender().isMale()) {
            bodyEvaluateSheet = fatEvaluateSheet_Man;
        } else {
            bodyEvaluateSheet = fatEvaluateSheet_Woman;
        }

        return evaluateSheet(fat, bodyEvaluateSheet);
    }

    public EvaluationResult evaluateBodyWater(float water) {
        List<sheetEntry> bodyEvaluateSheet;

        if (evalUser.getGender().isMale()) {
            bodyEvaluateSheet = waterEvaluateSheet_Man;
        } else {
            bodyEvaluateSheet = waterEvaluateSheet_Woman;
        }

        return evaluateSheet(water, bodyEvaluateSheet);
    }

    public EvaluationResult evaluateBodyMuscle(float muscle) {
        List<sheetEntry> bodyEvaluateSheet;

        if (evalUser.getGender().isMale()) {
            bodyEvaluateSheet = muscleEvaluateSheet_Man;
        } else {
            bodyEvaluateSheet = muscleEvaluateSheet_Woman;
        }

        return evaluateSheet(muscle, bodyEvaluateSheet);
    }

    public EvaluationResult evaluateBMI(float bmi) {
        List<sheetEntry> bodyEvaluateSheet;

        if (evalUser.getGender().isMale()) {
            bodyEvaluateSheet =  bmiEvaluateSheet_Man;
        } else {
            bodyEvaluateSheet = bmiEvaluateSheet_Woman;
        }

        return evaluateSheet(bmi, bodyEvaluateSheet);
    }

    public EvaluationResult evaluateWaist(float waist) {
        List<sheetEntry> bodyEvaluateSheet;

        if (evalUser.getGender().isMale()) {
            bodyEvaluateSheet =  waistEvaluateSheet_Man;
        } else {
            bodyEvaluateSheet = waistEvaluateSheet_Woman;
        }

        return evaluateSheet(waist, bodyEvaluateSheet);
    }

    public EvaluationResult evaluateWHtR(float whrt) {
        return evaluateSheet(whrt, whrtEvaluateSheet);
    }

    public EvaluationResult evaluateWHR(float whr) {
        List<sheetEntry> bodyEvaluateSheet;

        if (evalUser.getGender().isMale()) {
            bodyEvaluateSheet =  whrEvaluateSheet_Man;
        } else {
            bodyEvaluateSheet = whrEvaluateSheet_Woman;
        }

        return evaluateSheet(whr, bodyEvaluateSheet);
    }

    public EvaluationResult evaluateVisceralFat(float visceralFat) {
        return evaluateSheet(visceralFat, visceralFatEvaluateSheet);
    }

    private EvaluationResult evaluateSheet(float value, List<sheetEntry> sheet) {
        for (int i=0; i < sheet.size(); i++) {
            sheetEntry curEntry = sheet.get(i);

            if (curEntry.lowAge <= userAge && curEntry.maxAge >= userAge) {
                if (value < curEntry.lowLimit) { // low
                    return new EvaluationResult(value, curEntry.lowLimit, curEntry.highLimit, EvaluationResult.EVAL_STATE.LOW);
                } else if (value >= curEntry.lowLimit && value <= curEntry.highLimit) { // normal
                    return new EvaluationResult(value, curEntry.lowLimit, curEntry.highLimit, EvaluationResult.EVAL_STATE.NORMAL);
                } else if (value > curEntry.highLimit) { //high
                    return new EvaluationResult(value, curEntry.lowLimit, curEntry.highLimit, EvaluationResult.EVAL_STATE.HIGH);
                }
            }
        }

        return new EvaluationResult(0, -1, -1, EvaluationResult.EVAL_STATE.UNDEFINED);
    }
}
