package com.koyo.screenwarden

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment

class ThemeFragment : Fragment(R.layout.fragment_theme) {

    private lateinit var themeSpinner: Spinner
    private lateinit var customImageGroup: View
    private lateinit var customImageBtn: Button

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            ThemeManager.setCustomUri(it)
            requireActivity().let { a -> (a as? MainActivity)?.applyTheme() }
            updateCustomImageVisibility()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        themeSpinner = view.findViewById(R.id.theme_spinner)
        customImageGroup = view.findViewById(R.id.custom_image_group)
        customImageBtn = view.findViewById(R.id.custom_image_btn)

        setupThemePicker()
        customImageBtn.setOnClickListener { imagePicker.launch(arrayOf("image/*")) }
    }

    private fun setupThemePicker() {
        val themes = ThemeManager.getAllThemes()
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            themes.map { it.label }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        themeSpinner.adapter = adapter
        themeSpinner.setSelection(themes.indexOf(ThemeManager.getCurrentTheme()))

        themeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var ready = false
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!ready) { ready = true; return }
                ThemeManager.setTheme(themes[pos])
                requireActivity().let { a -> (a as? MainActivity)?.applyTheme() }
                updateCustomImageVisibility()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        updateCustomImageVisibility()
    }

    private fun updateCustomImageVisibility() {
        customImageGroup.visibility = if (ThemeManager.isCustom()) View.VISIBLE else View.GONE
    }
}
