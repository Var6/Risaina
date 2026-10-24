package com.risaina.app.core.common

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val type: DispatcherType)

enum class DispatcherType { IO, Default, Main }

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
