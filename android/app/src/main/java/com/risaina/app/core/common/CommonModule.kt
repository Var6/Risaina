package com.risaina.app.core.common

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CommonModule {
    @Provides @Singleton fun timeProvider(): TimeProvider = SystemTimeProvider()

    @Provides @Dispatcher(DispatcherType.IO) fun io(): CoroutineDispatcher = Dispatchers.IO
    @Provides @Dispatcher(DispatcherType.Default) fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
    @Provides @Dispatcher(DispatcherType.Main) fun main(): CoroutineDispatcher = Dispatchers.Main

    @Provides @Singleton @ApplicationScope
    fun applicationScope(@Dispatcher(DispatcherType.Default) dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)
}
