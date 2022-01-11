package com.health.openscale.core.bluetooth.lib;

// This class is similar to OneByoneLib, but the way measures are computer are slightly different
public class OneByoneNewLib {

    private int sex;
    private int age;
    private float height;
    private int peopleType; // low activity = 0; medium activity = 1; high activity = 2

    public OneByoneNewLib(int sex, int age, float height, int peopleType) {
        this.sex = sex;
        this.age = age;
        this.height = height;
        this.peopleType = peopleType;
    }

    public float getBMI(float weight) {
        float bmi = weight / (((height * height) / 100.0f) / 100.0f);
        return getBounded(bmi, 10, 90);
    }

    public float getLBM(float weight, int impedance) {
        float lbmCoeff = height / 100 * height / 100 * 9.058F;
        lbmCoeff += 12.226;
        lbmCoeff += weight * 0.32;
        lbmCoeff -= impedance * 0.0068;
        lbmCoeff -= age * 0.0542;
        return lbmCoeff;
    }



    public float getBMMRCoeff(float weight){
        int bmmrCoeff = 20;
        if(sex == 1){
            bmmrCoeff = 21;
            if(age < 0xd){
                bmmrCoeff = 36;
            } else if(age < 0x10){
                bmmrCoeff = 30;
            } else if(age < 0x12){
                bmmrCoeff = 26;
            } else if(age < 0x1e){
                bmmrCoeff = 23;
            } else if (age >= 0x32){
                bmmrCoeff = 20;
            }
        } else {
            if(age < 0xd){
                bmmrCoeff = 34;
            } else if(age < 0x10){
                bmmrCoeff = 29;
            } else if(age < 0x12){
                bmmrCoeff = 24;
            } else if(age < 0x1e){
                bmmrCoeff = 22;
            } else if (age >= 0x32){
                bmmrCoeff = 19;
            }
        }
        return bmmrCoeff;
    }

    public float getBMMR(float weight){
        float bmmr;
        if(sex == 1){
            bmmr = (weight * 14.916F + 877.8F) - height * 0.726F;
            bmmr -= age * 8.976;
        } else {
            bmmr = (weight * 10.2036F + 864.6F) - height * 0.39336F;
            bmmr -= age * 6.204;
        }

        return getBounded(bmmr, 500, 1000);
    }

    public float getBodyFatPercentage(float weight, int impedance) {
        float bodyFat = getLBM(weight, impedance);

        float bodyFatConst;
        if (sex == 0) {
            if (age < 0x32) {
                bodyFatConst = 9.25F;
            } else {
                bodyFatConst = 7.25F;
            }
        } else {
            bodyFatConst = 0.8F;
        }

        bodyFat -= bodyFatConst;

        if (sex == 0){
            if (weight < 50){
                bodyFat *= 1.02;
            } else if(weight > 60){
                bodyFat *= 0.96;
            } else if(weight > 160){
                bodyFat *= 1.03;
            }
        } else {
            if (weight < 61){
                bodyFat *= 0.98;
            }
        }

        return 100 * (1 - bodyFat / weight);
    }

    public float getBoneMass(float weight, int impedance){
        float lbmCoeff = getLBM(weight, impedance);

        float boneMassConst;
        if(sex == 1){
            boneMassConst = 0.18016894F;
        } else {
            boneMassConst = 0.245691014F;
        }

        boneMassConst = lbmCoeff * 0.05158F - boneMassConst;
        float boneMass;
        if(boneMassConst <= 2.2){
            boneMass = boneMassConst - 0.1F;
        } else {
            boneMass = boneMassConst + 0.1F;
        }

        return getBounded(boneMass, 0.5F, 8);
    }

    public float getMuscleMass(float weight, int impedance){
        float muscleMass = weight - getBodyFatPercentage(weight, impedance) * 0.01F * weight;
        muscleMass -= getBoneMass(weight, impedance);
        return getBounded(muscleMass, 10, 120);
    }

    public float getVisceralFat(float weight){
        float visceralFat;
        if (sex == 1) {
            if (height < weight * 1.6 + 63.0) {
                visceralFat =
                        age * 0.15F + ((weight * 305.0F) /((height * 0.0826F * height - height * 0.4F) + 48.0F) - 2.9F);
            }
            else {
                visceralFat = age * 0.15F + (weight * (height * -0.0015F + 0.765F) - height * 0.143F) - 5.0F;
            }
        }
        else {
            if (weight <= height * 0.5 - 13.0) {
                visceralFat = age * 0.07F + (weight * (height * -0.0024F + 0.691F) - height * 0.027F) - 10.5F;
            }
            else {
                visceralFat = age * 0.07F + ((weight * 500.0F) / ((height * 1.45F + height * 0.1158F * height) - 120.0F) - 6.0F);
            }
        }

        return getBounded(visceralFat, 1, 50);
    }

    public float getWaterPercentage(float weight, int impedance){
        float waterPercentage = (100 - getBodyFatPercentage(weight, impedance)) * 0.7F;
        if (waterPercentage > 50){
            waterPercentage *= 0.98;
        } else {
            waterPercentage *= 1.02;
        }

        return getBounded(waterPercentage, 35, 75);
    }

    public float getProteinPercentage(float weight, int impedance){
        return (
                (100.0F - getBodyFatPercentage(weight, impedance))
                        - getWaterPercentage(weight, impedance) * 1.08F
                )
                    - (getBoneMass(weight, impedance) / weight) * 100.0F;
    }


    private float getBounded(float value, float lowerBound, float upperBound){
        if(value < lowerBound){
            return lowerBound;
        } else if (value > upperBound){
            return upperBound;
        }
        return value;
    }

}
