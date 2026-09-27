package org.futo.voiceinput.shared

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext

class InferenceCancelledException : Exception()

// All inference runs sequentially on this dispatcher so a running recognition
// can be cancelled without overlapping model access.
@OptIn(DelicateCoroutinesApi::class)
val inferenceContext = newSingleThreadContext("canary-inference")
