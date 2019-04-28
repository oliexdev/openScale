package com.health.openscale.core.garminsync;

import android.content.Context;
import android.os.AsyncTask;

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;

import java.io.File;
import java.lang.ref.WeakReference;

import timber.log.Timber;

public class ExportToGarminBackgroundTask extends AsyncTask<Void, Void, Boolean> {
    private final WeakReference<ScaleMeasurement> weakScaleMeasurement;
    private final WeakReference<Context> weakContext;
    private final TaskListener taskListener;

    public interface TaskListener {
        public void onExportToGarminTaskFinished(Boolean result);
    }

    public ExportToGarminBackgroundTask(Context context, ScaleMeasurement scaleMeasurement, TaskListener taskListener) {
        this.weakScaleMeasurement = new WeakReference<>(scaleMeasurement);
        this.weakContext = new WeakReference<>(context);
        this.taskListener = taskListener;
    }

    @Override
    protected Boolean doInBackground(Void... voids) {
        Boolean result = true;
        ScaleMeasurement scaleMeasurement = this.weakScaleMeasurement.get();
        Context context = this.weakContext.get();

        if (context == null || scaleMeasurement == null) {
            return false;
        }

        GarminConnect garminConnect = new GarminConnect();

        try {
            OpenScale openScale = OpenScale.getInstance();
            ScaleUser user = openScale.getSelectedScaleUser();


            if (garminConnect.SignIn(user.getGarminLogin(), user.getGarminPassword())) {
                File output = Export.BuildFitFile(context, scaleMeasurement);
                garminConnect.UploadFitFile(output);
            } else {
                result = false;
            }
        } catch (Exception ex) {
            result = false;
            Timber.e(ex, "GarminException");
        } finally {
            garminConnect.Close();
        }

        return result;
    }

    @Override
    protected void onPostExecute(Boolean result) {
        super.onPostExecute(result);

        if (taskListener != null) {
            taskListener.onExportToGarminTaskFinished(result);
        }
    }
}