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

package com.health.openscale.core.bluetooth.lib;

public class OneByoneLib {
    private int sex; // male = 1; female = 0
    private int age;
    private float height;
    private int peopleType; // low activity = 0; medium activity = 1; high activity = 2

    public OneByoneLib(int sex, int age, float height, int peopleType) {
        this.sex = sex;
        this.age = age;
        this.height = height;
        this.peopleType = peopleType;
    }

    public float getBMI(float weight) {
        return weight / (((height * height) / 100.0f) / 100.0f);
    }

    public float getLBM(float weight, float bodyFat) {
        return weight - (bodyFat / 100.0f * weight);
    }

    public float getMuscle(float weight, float impedanceValue){
        return (float)((height * height / impedanceValue * 0.401) + (sex * 3.825) - (age * 0.071) + 5.102) / weight * 100.0f;
    }

    public float getWater(float bodyFat) {
        float coeff;
        float water = (100.0f - bodyFat) * 0.7f;

        if (water < 50) {
            coeff = 1.02f;
        } else {
            coeff = 0.98f;
        }

        return coeff * water;
    }

    public float getBoneMass(float weight, float impedanceValue) {
        float boneMass, sexConst , peopleCoeff  = 0.0f;

        switch (peopleType) {
            case 0:
                peopleCoeff = 1.0f;
                break;
            case 1:
                peopleCoeff = 1.0427f;
                break;
            case 2:
                peopleCoeff = 1.0958f;
                break;
        }

        boneMass = (9.058f * (height / 100.0f) * (height / 100.0f) + 12.226f + (0.32f * weight)) - (0.0068f * impedanceValue);

        if (sex == 1) { // male
            sexConst = 3.49305f;
        } else {
            sexConst = 4.76325f;
        }

        boneMass = boneMass - sexConst - (age * 0.0542f) * peopleCoeff;

        if (boneMass <= 2.2f) {
            boneMass = boneMass - 0.1f;
        } else {
            boneMass = boneMass + 0.1f;
        }

        boneMass = boneMass * 0.05158f;

        if (0.5f > boneMass) {
            return 0.5f;
        } else if (boneMass > 8.0f) {
            return 8.0f;
        }

        return boneMass;
    }

    public float getVisceralFat(float weight) {
        float visceralFat;

        if (sex == 1) {
            if (height < ((1.6f * weight) + 63.0f)) {
                visceralFat = (((weight * 305.0f) / (0.0826f * height * height - (0.4f * height) + 48.0f)) - 2.9f) + ((float)age * 0.15f);

                if (peopleType == 0) {
                    return visceralFat;
                } else {
                    return subVisceralFat_A(visceralFat);
                }
            } else {
                visceralFat = (((float)age * 0.15f) + ((weight * (-0.0015f * height + 0.765f)) - height * 0.143f)) - 5.0f;

                if (peopleType == 0) {
                    return visceralFat;
                } else {
                    return subVisceralFat_A(visceralFat);
                }
            }

        } else {
            if (((0.5f * height) - 13.0f) > weight) {
                visceralFat = (((float)age * 0.07f) + ((weight * (-0.0024f * height + 0.691f)) - (height * 0.027f))) - 10.5f;

                if (peopleType != 0) {
                    return subVisceralFat_A(visceralFat);
                } else {
                    return visceralFat;
                }

            } else {
                visceralFat = (weight * 500.0f) / (((1.45f * height) + 0.1158f * height * height) - 120.0f) - 6.0f + ((float)age * 0.07f);

                if (peopleType == 0) {
                    return visceralFat;
                } else {
                    return subVisceralFat_A(visceralFat);
                }
            }

        }
    }

    private float subVisceralFat_A(float visceralFat) {

        if (peopleType != 0) {
            if (10.0f <= visceralFat) {

                return subVisceralFat_B(visceralFat);
            } else {
                visceralFat = visceralFat - 4.0f;
                return visceralFat;
            }
        } else {
            if (10.0f > visceralFat) {
                visceralFat = visceralFat - 2.0f;
                return visceralFat;
            } else {
                return subVisceralFat_B(visceralFat);
            }
        }
    }

    private float subVisceralFat_B(float visceralFat) {
        if (visceralFat < 10.0f) {
            visceralFat = visceralFat * 0.85f;
            return visceralFat;
        } else {

            if (20.0f < visceralFat) {
                visceralFat = visceralFat * 0.85f;
                return visceralFat;
            } else {
                visceralFat = visceralFat * 0.8f;
                return visceralFat;
            }
        }
    }

    public float getBodyFat(float weight, float impedanceValue) {
        float bodyFatConst=0;

        if (impedanceValue >= 1200.0f) bodyFatConst = 8.16f;
        else if (impedanceValue >= 200.0f) bodyFatConst = 0.0068f * impedanceValue;
        else if (impedanceValue >= 50.0f) bodyFatConst = 1.36f;

        float peopleTypeCoeff, bodyVar, bodyFat;

        if (peopleType == 0) {
            peopleTypeCoeff = 1.0f;
        } else {
            if (peopleType == 1) {
                peopleTypeCoeff = 1.0427f;
            } else {
                peopleTypeCoeff = 1.0958f;
            }
        }

        bodyVar = (9.058f * height) / 100.0f;
        bodyVar = bodyVar * height;
        bodyVar = bodyVar / 100.0f + 12.226f;
        bodyVar = bodyVar + 0.32f * weight;
        bodyVar = bodyVar - bodyFatConst;

        if (age > 0x31) {
            bodyFatConst = 7.25f;

            if (sex == 1) {
                bodyFatConst = 0.8f;
            }
        } else {
            bodyFatConst = 9.25f;

            if (sex == 1) {
                bodyFatConst = 0.8f;
            }
        }

        bodyVar = bodyVar - bodyFatConst;
        bodyVar = bodyVar - (age * 0.0542f);
        bodyVar = bodyVar * peopleTypeCoeff;

        if (sex != 0) {
            if (61.0f > weight) {
                bodyVar *= 0.98f;
            }
        } else {
            if (50.0f > weight) {
                bodyVar *= 1.02f;
            }

            if (weight > 60.0f) {
                bodyVar *= 0.96f;
            }

            if (height > 160.0f) {
                bodyVar *= 1.03f;
            }
        }

        bodyVar = bodyVar / weight;
        bodyFat = 100.0f * (1.0f - bodyVar);

        if (1.0f > bodyFat) {
            return 1.0f;
        } else {
            if (bodyFat > 45.0f) {
                return 45.0f;
            } else {
                return bodyFat;
            }
        }
    }
}
