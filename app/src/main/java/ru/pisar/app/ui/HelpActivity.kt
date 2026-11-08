package ru.pisar.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ru.pisar.app.R
import ru.pisar.app.databinding.ActivityHelpBinding

class HelpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHelpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.helpText.text = loadReference()
    }

    private fun loadReference(): String {
        val raw = runCatching {
            assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrElse {
            getString(R.string.help_body)
        }
        return raw
            .replace(Regex("(?m)^#{1,6}\\s*"), "")
            .replace("**", "")
            .replace("`", "")
            .trim()
    }

    private companion object {
        const val ASSET = "СПРАВКА.md"
    }
}
