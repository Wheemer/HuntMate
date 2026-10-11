package com.huntmate.gps

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class AppHubActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_hub)
        findViewById<Button>(R.id.closeHubButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.smallGameRecordsButton).setOnClickListener { startActivity(Intent(this, SmallGameRecordsActivity::class.java)) }
    }

}
