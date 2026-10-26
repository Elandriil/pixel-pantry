package app.pixelpantry.tv.di

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app-wide scope (scanner polls, STOP reports on leaving the player, the settings seed, toasts). A failure in
 * one of its coroutines is handed to [onUncaught] (logged) instead of crashing the app; the scope keeps running.
 */
fun appScope(onUncaught: (Throwable) -> Unit): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t -> onUncaught(t) })
