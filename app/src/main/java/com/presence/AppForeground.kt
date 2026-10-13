package com.presence

import kotlinx.coroutines.flow.MutableStateFlow

/** Whether FACEDOWN's screen is in front. Sessions start their server clock only once the user is back here. */
object AppForeground {
    val resumed = MutableStateFlow(false)
}
