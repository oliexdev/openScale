package com.health.openscale.core;

public class ScaleCalculator {

    private float weight;
    private float fat;
    private float water;
    private float muscle;
    private float waist;
    private float hip;

    public ScaleCalculator(ScaleData scaleData) {
        weight = scaleData.weight;
        fat = scaleData.fat;
        water = scaleData.water;
        muscle = scaleData.weight;
        waist = scaleData.waist;
        hip = scaleData.hip;
    }

    public float getBMI(int body_height) {
        return weight / ((body_height / 100.0f)*(body_height / 100.0f));
    }

    public float getWHtR(int body_height) {
        return waist / (float)body_height;
    }

    public float getWHR() {
        if (hip == 0) {
            return 0;
        }

        return waist / hip;
    }

}
