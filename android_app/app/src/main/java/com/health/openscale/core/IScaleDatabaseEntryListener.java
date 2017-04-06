package com.health.openscale.core;

import android.content.Context;

public interface IScaleDatabaseEntryListener
{
    void entryChanged(Context context, ScaleData data);
}
