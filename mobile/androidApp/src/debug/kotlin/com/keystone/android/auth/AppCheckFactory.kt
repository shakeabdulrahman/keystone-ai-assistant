package com.keystone.android.auth

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds (and the emulator, which can't pass Play Integrity) use the debug provider.
 * On first launch it logs a debug secret (Logcat, tag "DebugAppCheckProvider"); add it in
 * Firebase console → App Check → Apps → ⋮ → Manage debug tokens. See docs/deployment.md.
 */
internal fun appCheckProviderFactory(): AppCheckProviderFactory = DebugAppCheckProviderFactory.getInstance()
