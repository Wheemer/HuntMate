package com.huntmate.gps

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * HuntMate's app hub. Hunting & Fishing is the primary app; optional outdoor
 * products can be installed separately and launched without sharing their data.
 *
 * A real download URL is supplied at build/release time through the
 * huntmateOutdoorDownloadUrl Gradle property. We intentionally do not embed a
 * made-up URL in the application.
 */
class AppHubActivity : AppCompatActivity() {
    companion object {
        private const val OUTDOOR_PACKAGE = "com.huntmate.outdoor"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_hub)
        findViewById<Button>(R.id.closeHubButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.smallGameRecordsButton).setOnClickListener { startActivity(Intent(this, SmallGameRecordsActivity::class.java)) }
        findViewById<Button>(R.id.outdoorButton).setOnClickListener { openOrOfferOutdoor() }
        refreshOutdoorButton()
    }

    override fun onResume() {
        super.onResume()
        refreshOutdoorButton()
    }

    private fun refreshOutdoorButton() {
        val installed = packageManager.getLaunchIntentForPackage(OUTDOOR_PACKAGE) != null
        val button = findViewById<Button>(R.id.outdoorButton)
        button.text = if (installed) "Open HuntMate Outdoor" else "Get HuntMate Outdoor"
        findViewById<TextView>(R.id.outdoorStatus).text = if (installed) {
            "Installed • your Outdoor app stays separate from Hunting & Fishing data"
        } else {
            "Optional companion app • Hiking, Camping, Water Sports, Backcountry and General Outdoor"
        }
    }

    private fun openOrOfferOutdoor() {
        val launchIntent = packageManager.getLaunchIntentForPackage(OUTDOOR_PACKAGE)
        if (launchIntent != null) {
            startActivity(launchIntent)
            return
        }

        val url = BuildConfig.OUTDOOR_DOWNLOAD_URL.trim()
        if (url.isEmpty()) {
            Toast.makeText(
                this,
                "Outdoor download center is not configured in this build.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No browser is available to open the download center.", Toast.LENGTH_LONG).show()
        }
    }
}
