package com.health.openscale.gui.permissions;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;

import com.health.openscale.R;
import com.karumi.dexter.listener.PermissionDeniedResponse;
import com.karumi.dexter.listener.PermissionGrantedResponse;
import com.karumi.dexter.listener.single.BasePermissionListener;

public class LocationPermissionListener extends BasePermissionListener
{
    private final Handler handler;
    private final Runnable onPermissionGranted;
    private Context context;

    public LocationPermissionListener(Context context, Handler handler, Runnable onPermissionGranted)
    {
        this.context = context;
        this.handler = handler;
        this.onPermissionGranted = onPermissionGranted;
    }

    @Override
    public void onPermissionGranted(PermissionGrantedResponse response)
    {
        handler.post(onPermissionGranted);
    }

    @Override
    public void onPermissionDenied(PermissionDeniedResponse response)
    {
        new AlertDialog.Builder(context).setTitle(R.string.permission_location_denied_title)
                                        .setMessage(R.string.permission_location_denied_message)
                                        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener()
                                        {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which)
                                            {
                                                dialog.dismiss();
                                            }
                                        })
                                        .show();
    }
}

