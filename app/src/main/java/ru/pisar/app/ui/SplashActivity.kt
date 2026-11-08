package ru.pisar.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.pisar.app.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.setOnClickListener { openMain() }

        lifecycleScope.launch {
            delay(1400)
            openMain()
        }
    }

    private var opened = false

    private fun openMain() {
        if (opened) return
        opened = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
