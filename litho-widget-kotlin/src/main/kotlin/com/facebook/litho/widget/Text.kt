/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.litho.widget

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Typeface.DEFAULT
import android.graphics.Typeface.NORMAL
import android.text.TextUtils
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import com.facebook.litho.Dimen
import com.facebook.litho.Handle
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Style
import com.facebook.litho.dp
import com.facebook.litho.kotlinStyle
import com.facebook.litho.sp

const val SHOULD_INCLUDE_FONT_PADDING = TextSpec.shouldIncludeFontPadding
const val CLIP_TO_BOUNDS = TextSpec.clipToBounds

/**
 * Temporary builder function for creating [TextSpec] components. In the future it will either be
 * auto-generated or modified to have the final set of parameters.
 */
@Suppress("NOTHING_TO_INLINE", "FunctionName")
inline fun ResourcesScope.Text(
    text: CharSequence?,
    style: Style? = null,
    @ColorInt textColor: Int = Color.BLACK,
    textSize: Dimen = 14.sp,
    textStyle: Int = NORMAL,
    typeface: Typeface? = DEFAULT,
    @ColorInt shadowColor: Int = Color.GRAY,
    shadowRadius: Dimen = 0.dp,
    alignment: TextAlignment = TextAlignment.TEXT_START,
    verticalGravity: VerticalGravity = VerticalGravity.TOP,
    isSingleLine: Boolean = false,
    ellipsize: TextUtils.TruncateAt? = null,
    lineSpacingMultiplier: Float = 1f,
    minLines: Int = 0,
    maxLines: Int = Int.MAX_VALUE,
    includeFontPadding: Boolean = SHOULD_INCLUDE_FONT_PADDING,
    accessibleClickableSpans: Boolean = false,
    clipToBounds: Boolean = CLIP_TO_BOUNDS,
    handle: Handle? = null,
    customEllipsisText: CharSequence? = null,
    @ColorInt backgroundColor: Int? = null,
    @ColorInt highlightColor: Int? = null,
    @AttrRes defStyleAttr: Int = 0,
    @StyleRes defStyleRes: Int = 0,
): Text =
    Text.create(context, defStyleAttr, defStyleRes)
        .text(text)
        .textColor(textColor)
        .textSizePx(textSize.toPixels())
        .textStyle(textStyle)
        .typeface(typeface)
        .shadowColor(shadowColor)
        .shadowRadiusPx(shadowRadius.toPixels().toFloat())
        .alignment(alignment)
        .verticalGravity(verticalGravity)
        .spacingMultiplier(lineSpacingMultiplier)
        .isSingleLine(isSingleLine)
        .minLines(minLines)
        .maxLines(maxLines)
        .shouldIncludeFontPadding(includeFontPadding)
        .accessibleClickableSpans(accessibleClickableSpans)
        .clipToBounds(clipToBounds)
        .apply { customEllipsisText?.let { customEllipsisText(customEllipsisText) } }
        .apply { ellipsize?.let { ellipsize(it) } }
        .handle(handle)
        .apply { backgroundColor?.let { backgroundColor(backgroundColor) } }
        .apply { highlightColor?.let { highlightColor(it) } }
        .kotlinStyle(style)
        .build()
