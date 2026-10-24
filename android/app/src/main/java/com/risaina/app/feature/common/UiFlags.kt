package com.risaina.app.feature.common

import javax.inject.Inject
import javax.inject.Singleton

/** Tiny cross-screen flags that do not deserve persistence. */
@Singleton
class UiFlags @Inject constructor() {
    @Volatile var justPaired: Boolean = false
}
