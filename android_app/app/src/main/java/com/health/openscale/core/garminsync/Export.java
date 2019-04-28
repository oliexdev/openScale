/*
  Copyright 2012 Sébastien Vrillaud
  
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at
  
      http://www.apache.org/licenses/LICENSE-2.0
  
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/
package com.health.openscale.core.garminsync;

import android.content.Context;

import com.garmin.fit.DateTime;
import com.garmin.fit.FileEncoder;
import com.garmin.fit.WeightScaleMesg;
import com.health.openscale.core.datatypes.ScaleMeasurement;

import java.io.File;

class Export {
  static File BuildFitFile(Context context, ScaleMeasurement measurement)  {
    String filename = "export.fit";
    File result = new File(context.getFilesDir(), filename);
    result.delete();

    FileEncoder encoder = new FileEncoder(result);

    WeightScaleMesg wm;
      wm = new WeightScaleMesg();

      float muscleMass = measurement.getWeight() != 0.0
              ? measurement.getWeight() * measurement.getMuscle() / 100.0f
              : 0;

      wm.setTimestamp(new DateTime(measurement.getDateTime()));
      wm.setWeight(measurement.getWeight());
      wm.setPercentFat(measurement.getFat());
      wm.setPercentHydration(measurement.getWater());
      wm.setMuscleMass(muscleMass);
      encoder.write(wm);

    encoder.close();

    return result;
  }
}
