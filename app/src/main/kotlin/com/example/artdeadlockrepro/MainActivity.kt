package com.example.artdeadlockrepro

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Minimal interactive UI so it's obvious whether the process is alive or wedged:
 *  - a "heartbeat" that ticks once per second on the main thread;
 *  - +/- buttons with a counter.
 *
 * While the app is healthy the heartbeat keeps counting and the buttons respond.
 * The moment the ART/JDWP deadlock hits, the main thread stops — the heartbeat freezes
 * and the buttons do nothing. That frozen heartbeat is the visible "hung" signal.
 */
class MainActivity : Activity() {

    private var counter = 0
    private var ticks = 0
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var counterView: TextView
    private lateinit var heartbeatView: TextView

    private val heartbeat = object : Runnable {
        override fun run() {
            ticks++
            heartbeatView.text = "UI heartbeat: ${ticks}s  (frozen = deadlock)"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 96, 48, 48)
        }

        val title = TextView(this).apply {
            text = "ART/JDWP deadlock repro\n\n" +
                "Tap +/- and watch the heartbeat.\n" +
                "When they freeze, the VM is deadlocked."
            textSize = 16f
        }
        heartbeatView = TextView(this).apply {
            text = "UI heartbeat: 0s  (frozen = deadlock)"
            textSize = 18f
            setPadding(0, 48, 0, 24)
        }
        counterView = TextView(this).apply {
            textSize = 30f
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }
        val minus = Button(this).apply {
            text = "  −  "
            textSize = 28f
            setOnClickListener { counter--; render() }
        }
        val plus = Button(this).apply {
            text = "  +  "
            textSize = 28f
            setOnClickListener { counter++; render() }
        }
        row.addView(minus)
        row.addView(plus)

        root.addView(title)
        root.addView(heartbeatView)
        root.addView(counterView)
        root.addView(row)
        setContentView(root)

        render()
        handler.post(heartbeat)

        // Start the workload after the first frame so the UI is up first.
        root.post { Storm.startOnce(applicationContext) }
    }

    private fun render() {
        counterView.text = "counter: $counter"
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(heartbeat)
    }
}
