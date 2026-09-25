package com.knowyourcase.notice.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

enum class TextRole(val sizeSp: Float, val lineMultiplier: Float) {
    DISPLAY(UiTokens.Type.DISPLAY, 1.15f),
    HEADLINE(UiTokens.Type.HEADLINE, 1.2f),
    TITLE(UiTokens.Type.TITLE, 1.25f),
    BODY(UiTokens.Type.BODY, UiTokens.Type.BODY_LINE),
    LABEL(UiTokens.Type.LABEL, UiTokens.Type.LABEL_LINE),
    CAPTION(UiTokens.Type.CAPTION, 1.35f)
}

enum class StateKind { EMPTY, LOADING, ERROR, SUCCESS }

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.themeColor(attr: Int): Int {
    val value = TypedValue()
    theme.resolveAttribute(attr, value, true)
    return if (value.resourceId != 0) getColor(value.resourceId) else value.data
}

fun TextView.applyType(role: TextRole, strong: Boolean = false) {
    textSize = role.sizeSp
    setLineSpacing(0f, role.lineMultiplier)
    setTypeface(typeface, if (strong) Typeface.BOLD else Typeface.NORMAL)
}

fun Context.roundedSurface(attr: Int, radiusDp: Int): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(themeColor(attr))
    }

fun Context.roundedColor(colorRes: Int, radiusDp: Int): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(getColor(colorRes))
    }

fun Context.designCard(): MaterialCardView = MaterialCardView(this).apply {
    radius = dp(UiTokens.Radius.LARGE).toFloat()
    cardElevation = 0f
    strokeWidth = dp(1)
    strokeColor = themeColor(com.google.android.material.R.attr.colorOutlineVariant)
    setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))
}

fun Context.primaryButton(
    label: String,
    iconRes: Int? = null,
    click: () -> Unit
): MaterialButton = MaterialButton(this).apply {
    text = label
    isAllCaps = false
    isSingleLine = true
    minHeight = dp(UiTokens.MIN_TOUCH)
    applyTypeToButton(TextRole.LABEL, true)
    if (iconRes != null) {
        setIconResource(iconRes)
        iconSize = dp(UiTokens.Icon.ACTION)
        iconPadding = dp(UiTokens.Space.XS)
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
    }
    setOnClickListener { click() }
}

fun Context.outlineButton(
    label: String,
    iconRes: Int? = null,
    click: () -> Unit
): MaterialButton = MaterialButton(
    this,
    null,
    com.google.android.material.R.attr.materialButtonOutlinedStyle
).apply {
    text = label
    isAllCaps = false
    isSingleLine = true
    minHeight = dp(UiTokens.MIN_TOUCH)
    applyTypeToButton(TextRole.LABEL, true)
    if (iconRes != null) {
        setIconResource(iconRes)
        iconSize = dp(UiTokens.Icon.ACTION)
        iconPadding = dp(UiTokens.Space.XS)
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
    }
    setOnClickListener { click() }
}

private fun MaterialButton.applyTypeToButton(role: TextRole, strong: Boolean) {
    textSize = role.sizeSp
    setLineSpacing(0f, role.lineMultiplier)
    setTypeface(typeface, if (strong) Typeface.BOLD else Typeface.NORMAL)
}

fun Context.sectionTitle(textValue: String): TextView = TextView(this).apply {
    text = textValue
    applyType(TextRole.TITLE, true)
    setPadding(0, 0, 0, dp(UiTokens.Space.SM))
}

fun Context.statePanel(
    kind: StateKind,
    titleText: String,
    messageText: String,
    iconRes: Int,
    actionText: String? = null,
    action: (() -> Unit)? = null
): MaterialCardView = designCard().apply {
    val body = LinearLayout(this@statePanel).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(
            dp(UiTokens.Space.MD),
            dp(UiTokens.Space.LG),
            dp(UiTokens.Space.MD),
            dp(UiTokens.Space.LG)
        )
    }
    body.addView(ImageView(this@statePanel).apply {
        setImageResource(iconRes)
        setColorFilter(
            themeColor(
                when (kind) {
                    StateKind.ERROR -> com.google.android.material.R.attr.colorError
                    StateKind.SUCCESS -> com.google.android.material.R.attr.colorPrimary
                    else -> com.google.android.material.R.attr.colorOnSurfaceVariant
                }
            )
        )
        contentDescription = null
    }, LinearLayout.LayoutParams(dp(UiTokens.Icon.EMPTY), dp(UiTokens.Icon.EMPTY)).apply {
        bottomMargin = dp(UiTokens.Space.SM)
    })
    body.addView(TextView(this@statePanel).apply {
        text = titleText
        applyType(TextRole.TITLE, true)
        gravity = Gravity.CENTER
    })
    body.addView(TextView(this@statePanel).apply {
        text = messageText
        applyType(TextRole.BODY)
        gravity = Gravity.CENTER
        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        setPadding(0, dp(UiTokens.Space.XS), 0, 0)
    })
    if (!actionText.isNullOrBlank() && action != null) {
        body.addView(outlineButton(actionText, click = action), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(UiTokens.Space.MD) })
    }
    addView(body)
}
