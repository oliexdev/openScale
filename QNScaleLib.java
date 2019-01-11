package com.health.openscale.core.bluetooth.lib;

/**
 * Created by dockerss on 12/25/2018.
 */

 
    public class QNScaleLib {

    private final double impedance;
    private final double bmi;
    private final boolean isMale;
    private final int sex; // male = 1; female = 0
    private final int age;
    private final float height;
    private int peopleType; // low activity = 0; medium activity = 1; high activity = 2


    public QNScaleLib(int sex, int age, float height, int peopleType, double weight, int resistance2) {
        this.sex = sex;
        this.age = age;
        this.height = height;
        this.peopleType = peopleType;
        this.isMale = sex == 1 ? true:false;
        this.bmi = weight * 1e4 / (height * height);
        impedance = resistance2 < 410 ? 3.0 : 0.3 * (resistance2 - 400);
    }




public double getFat( ) {
    double fat = isMale
            ? bmi * (1.479 + 4.4e-4 * impedance) + 0.1 * age - 21.764
            : bmi * (1.506 + 3.908e-4 * impedance) + 0.1 * age - 12.834;
    return fat;
}
    public double getWater( ) {
    double water = isMale
            ? 87.51 + (-1.162 * bmi - 0.00813 * impedance + 0.07594 * age)
            : 77.721 + (-1.148 * bmi - 0.00573 * impedance + 0.06448 * age);
        return water;
    }
    public double getMuscle( ) {
    double muscle = isMale
            ? 74.627 + (-0.811 * bmi - 0.00565 * impedance - 0.367 * age)
            : 57.0 + (-0.694 * bmi - 0.00344 * impedance - 0.255 * age);
        return muscle;
    }
    public double getBone() {
        double bone = isMale
                ? 7.829 + (-0.0855 * bmi - 5.92e-4 * impedance - 0.0389 * age)
                : 7.98 + (-0.0973 * bmi - 4.84e-4 * impedance - 0.036 * age);
        return bone;
    }
    }
