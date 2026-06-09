package com.bydmate.app.di

import com.bydmate.app.data.repository.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BootEntryPoint {
    fun settingsRepository(): SettingsRepository
}
