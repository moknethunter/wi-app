package com.mokasas.wikall

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Composition root: the application chooses its real engine here, not in the service. */
@Module
@InstallIn(SingletonComponent::class)
internal object OverlayEngineModule {
    @Provides
    @Singleton
    fun provideOverlayEngine(): OverlayEngine = EasyTierEngine()
}
