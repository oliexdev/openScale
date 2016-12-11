package com.health.openscale.core;

public class ScaleCalculator {

    public float weight;
    public float fat;
    public float water;
    public float muscle;
    public float waist;
    public float hip;
    public int body_height;

    public void setScaleData(ScaleData scaleData) {
        weight = scaleData.weight;
        fat = scaleData.fat;
        water = scaleData.water;
        muscle = scaleData.weight;
        waist = scaleData.waist;
        hip = scaleData.hip;
    }

    public float getBMI() {
        return weight / ((body_height / 100.0f)*(body_height / 100.0f));
    }

    public float getWHtR() {
        return waist / (float)body_height;
    }

    public float getWHR() {
        if (hip == 0) {
            return 0;
        }

        return waist / hip;
    }

}
