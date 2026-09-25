package com.knowyourcase.notice.ui

object UiTokens {
    object Space {
        const val XXS = 4
        const val XS = 8
        const val SM = 12
        const val MD = 16
        const val LG = 24
        const val XL = 32
    }

    object Radius {
        const val SMALL = 12
        const val MEDIUM = 16
        const val LARGE = 24
        const val SHEET = 28
        const val PILL = 999
    }

    object Type {
        const val DISPLAY = 32f
        const val HEADLINE = 24f
        const val TITLE = 20f
        const val BODY = 16f
        const val LABEL = 14f
        const val CAPTION = 12f

        const val BODY_LINE = 1.45f
        const val LABEL_LINE = 1.35f
    }

    object Icon {
        const val NAV = 24
        const val ACTION = 20
        const val SUPPORT = 24
        const val EMPTY = 40
    }

    object Stroke {
        const val THIN = 1
        const val FOCUSED = 2
    }

    object Motion {
        const val FAST = 200L
        const val STANDARD = 250L
        const val SLOW = 300L
    }

    object Size {
        const val TOUCH = 48
        const val PRIMARY_ACTION = 56
        const val SCANNER_ACTION = 56
        const val SCANNER_PHOTO = 64
        const val META_LABEL = 96
        const val PROGRESS = 40
        const val HANDLE_WIDTH = 40
        const val HANDLE_HEIGHT = 4
    }

    const val MIN_TOUCH = Size.TOUCH
}
