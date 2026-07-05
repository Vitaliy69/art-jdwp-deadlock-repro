package com.example.artdeadlockrepro

import android.app.Application

/**
 * Startup is deliberately cheap so the UI draws immediately and the app never *looks*
 * hung on a plain `Run`. The actual reproduction workload is started by [MainActivity]
 * after the first frame — see [Storm].
 */
class ReproApp : Application()
