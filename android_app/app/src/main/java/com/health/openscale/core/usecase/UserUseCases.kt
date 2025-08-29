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
package com.health.openscale.core.usecase

import com.health.openscale.core.data.User
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.model.UserEvaluationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collection of user-related use cases.
 *
 * Centralizes user-facing application logic so UI layers remain thin
 * and persistence-agnostic. Provides reactive accessors for the current
 * selection and convenient helpers to build domain-specific contexts.
 */
@Singleton
class UserUseCases @Inject constructor(
    private val databaseRepository: DatabaseRepository,
    private val settingsFacade: SettingsFacade,
    private val sync: SyncUseCases
) {
    private val supportedAppLangs = setOf("en", "de", "es", "fr")

    /** Observe all users stored in the database. */
    fun observeAllUsers(): Flow<List<User>> = databaseRepository.getAllUsers()

    /** Observe a single user by ID. */
    fun observeUserById(userId: Int): Flow<User?> = databaseRepository.getUserById(userId)

    /** Observe the currently selected user ID (may be null). */
    fun observeSelectedUserId(): Flow<Int?> = settingsFacade.currentUserId

    /**
     * Set the currently selected user ID and persist it.
     *
     * If a non-null ID is provided, validation ensures that the user exists.
     * Returns [Result.failure] if validation fails.
     */
    suspend fun setSelectedUserId(userId: Int?): Result<Unit> = runCatching {
        if (userId != null) {
            val exists = databaseRepository.getUserById(userId).first() != null
            require(exists) { "User not found: $userId" }
        }
        settingsFacade.setCurrentUserId(userId)
    }

    /** Observe the currently selected user object (may be null). */
    fun observeSelectedUser(): Flow<User?> =
        observeSelectedUserId().flatMapLatest { id ->
            if (id == null) flowOf(null) else observeUserById(id)
        }

    /** Build an evaluation context from a [User] domain model. */
    fun buildUserEvaluationContext(user: User): UserEvaluationContext =
        UserEvaluationContext(
            gender = user.gender,
            heightCm = user.heightCm,
            birthDateMillis = user.birthDate
        )

    /** Observe the evaluation context for the currently selected user. */
    fun observeUserEvaluationContext(): Flow<UserEvaluationContext?> =
        observeSelectedUser().map { user -> user?.let { buildUserEvaluationContext(it) } }

    /**
     * Restore the last selected user if still valid, otherwise select a sensible default.
     *
     * If the last selection is invalid or absent, this chooses the first available user
     * (if any) and persists the change. Returns the resulting selected user ID (or null
     * if no users exist).
     */
    suspend fun restoreOrSelectDefaultUser(): Result<Int?> = runCatching {
        val last = settingsFacade.currentUserId.first()
        val valid = last?.let { databaseRepository.getUserById(it).first() } != null
        if (valid) {
            last
        } else {
            val users = databaseRepository.getAllUsers().first()
            if (users.isNotEmpty()) {
                val firstId = users.first().id
                settingsFacade.setCurrentUserId(firstId)
                firstId
            } else {
                // No users -> keep selection null
                settingsFacade.setCurrentUserId(null)
                null
            }
        }
    }

    /** Insert a new user and return its row ID. */
    suspend fun addUser(user: User): Result<Long> = runCatching {
        databaseRepository.insertUser(user)
    }

    /** Update an existing user. */
    suspend fun updateUser(user: User): Result<Unit> = runCatching {
        databaseRepository.updateUser(user)
    }

    /** Delete all measurements for the given user. Returns number of deleted rows. */
    suspend fun purgeMeasurementsForUser(userId: Int): Result<Int> = runCatching {
        sync.triggerSyncClear("com.health.openscale.sync")
        sync.triggerSyncClear("com.health.openscale.sync.oss")
        sync.triggerSyncClear("com.health.openscale.sync.debug")
        databaseRepository.deleteAllMeasurementsForUser(userId)
    }

    /**
     * Delete a user. If the deleted user was currently selected, clear or remap selection.
     *
     * @param user the user to delete
     * @param reseatSelection if true and the deleted user was selected, attempt to select
     *                        another existing user (first in list); otherwise clear selection.
     * @return the new selected user ID after deletion (may be null), wrapped in [Result].
     */
    suspend fun deleteUser(
        user: User,
        reseatSelection: Boolean = true
    ): Result<Int?> = runCatching {
        val currentSelected = settingsFacade.currentUserId.first()
        databaseRepository.deleteUser(user)

        if (currentSelected == user.id) {
            if (reseatSelection) {
                val remaining = databaseRepository.getAllUsers().first()
                val newId = remaining.firstOrNull()?.id
                settingsFacade.setCurrentUserId(newId)
                newId
            } else {
                settingsFacade.setCurrentUserId(null)
                null
            }
        } else {
            currentSelected
        }
    }

    /** Observe effective app language with system fallback if unset/invalid. */
    fun observeAppLanguageCode(): Flow<String> =
        settingsFacade.appLanguageCode
            .map { stored -> stored?.takeIf { it in supportedAppLangs } ?: resolveDefaultAppLanguage() }
            .catch { emit(resolveDefaultAppLanguage()) }

    /** Set app language (ignored if blank). */
    suspend fun setAppLanguageCode(languageCode: String) {
        if (languageCode.isBlank()) return
        settingsFacade.setAppLanguageCode(languageCode)
    }

    /** Resolve default from system locale with safe fallback. */
    fun resolveDefaultAppLanguage(): String {
        val sys = Locale.getDefault().language
        return if (sys in supportedAppLangs) sys else "en"
    }
}
