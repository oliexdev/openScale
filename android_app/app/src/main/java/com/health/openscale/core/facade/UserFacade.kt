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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.facade

import com.health.openscale.core.data.User
import com.health.openscale.core.model.UserEvaluationContext
import com.health.openscale.core.usecase.UserUseCases
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Facade exposing user-related functionality to the UI layer.
 * Wraps and orchestrates underlying user use cases into a convenient API.
 */
@Singleton
class UserFacade @Inject constructor(
    private val userUseCases: UserUseCases
) {
    // --- Observables ---

    /** All users in DB. */
    fun observeAllUsers(): Flow<List<User>> = userUseCases.observeAllUsers()

    /** Currently selected user id (nullable). */
    fun observeSelectedUserId(): Flow<Int?> = userUseCases.observeSelectedUserId()

    /** Currently selected user (nullable). */
    fun observeSelectedUser(): Flow<User?> = userUseCases.observeSelectedUser()

    /** Evaluation context for the current user (nullable). */
    fun observeUserEvaluationContext(): Flow<UserEvaluationContext?> =
        userUseCases.observeUserEvaluationContext()

    /** Effective app language (stored or safe system fallback). */
    fun observeAppLanguageCode(): Flow<String> =
        userUseCases.observeAppLanguageCode()

    // --- Mutations / Commands ---

    /** Select a given user id (or clear with null). */
    suspend fun setSelectedUserId(userId: Int?): Result<Unit> =
        userUseCases.setSelectedUserId(userId)

    /** Restore last or choose a sensible default user; returns selected id or null. */
    suspend fun restoreOrSelectDefaultUser(): Result<Int?> =
        userUseCases.restoreOrSelectDefaultUser()

    /** Insert a new user; returns new row id. */
    suspend fun addUser(user: User): Result<Long> =
        userUseCases.addUser(user)

    /** Update an existing user. */
    suspend fun updateUser(user: User): Result<Unit> =
        userUseCases.updateUser(user)

    /** Delete all measurements for a user; returns number of deleted rows. */
    suspend fun deleteAllMeasurementsForUser(userId: Int): Result<Int> =
        userUseCases.purgeMeasurementsForUser(userId)

    /**
     * Delete a user. If it was selected, re-seat selection to another user (default true).
     * Returns the new selected user id (nullable).
     */
    suspend fun deleteUser(user: User, reseatSelection: Boolean = true): Result<Int?> =
        userUseCases.deleteUser(user, reseatSelection)

    /** Persist application language code. */
    suspend fun setAppLanguageCode(code: String): Result<Unit> = runCatching {
        userUseCases.setAppLanguageCode(code)
    }

    /** Resolve safe default language from system (non-reactive helper). */
    fun resolveDefaultAppLanguage(): String =
        userUseCases.resolveDefaultAppLanguage()
}
