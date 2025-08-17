/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.database.UserSettingsRepository

/**
 * Custom [WorkerFactory] for creating worker instances with injected dependencies.
 *
 * This factory is provided to WorkManager during its initialization (via [androidx.work.Configuration.Builder.setWorkerFactory])
 * to allow construction of workers that require dependencies beyond the default `Context` and `WorkerParameters`.
 */
class TaskWorkerFactory(
    // Dependencies provided by the Application class, to be passed to the workers.
    private val userSettingsRepository: UserSettingsRepository,
    private val databaseRepository: DatabaseRepository
) : WorkerFactory() {

    /**
     * Called by WorkManager to create an instance of a [ListenableWorker].
     *
     * @param appContext The application [Context].
     * @param workerClassName The fully-qualified class name of the worker to create.
     * @param workerParameters The [WorkerParameters] for the worker.
     * @return An instance of the [ListenableWorker], or `null` if this factory
     *         cannot create the requested worker type.
     */
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return when (workerClassName) {
            BackupWorker::class.java.name ->
                BackupWorker(
                    appContext,
                    workerParameters,
                    userSettingsRepository,
                    databaseRepository
                )

            // Add other 'when' branches here if you have other custom workers
            // that this factory should create.
            // e.g.:
            // AnotherCustomWorker::class.java.name ->
            //     AnotherCustomWorker(appContext, workerParameters, someOtherDependency)

            else ->
                null
        }
    }
}
