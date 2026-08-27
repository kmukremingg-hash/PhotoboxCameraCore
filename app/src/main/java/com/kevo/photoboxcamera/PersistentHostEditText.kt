package com.kevo.photoboxcamera

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.widget.EditText

class PersistentHostEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : EditText(context, attrs, defStyleAttr) {

    private val prefs = context.getSharedPreferences("photobox_remote", Context.MODE_PRIVATE)
    private val key = "host_address"
    private val defaultAddress = "10.87.194.242:8765"

    init {
        setText(prefs.getString(key, defaultAddress) ?: defaultAddress)
        setSelection(text.length)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val value = s?.toString()?.trim().orEmpty()
                if (value.isNotEmpty()) prefs.edit().putString(key, value).apply()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }
}
