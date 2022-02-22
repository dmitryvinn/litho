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

package com.facebook.litho.animated

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import com.facebook.litho.DynamicValue
import com.facebook.litho.Row
import com.facebook.litho.Style
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.px
import com.facebook.litho.testing.LithoViewRule
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

/** Unit tests for common styles defined in [DynamicValueStyles]. */
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(RobolectricTestRunner::class)
class DynamicValueStylesTest {

  @Rule @JvmField val lithoViewRule = LithoViewRule()

  @Test
  fun dynamic_backgroundColor_whenSet_isRespected() {
    val startValue: Int = Color.RED
    val backgroundColorDV: DynamicValue<Int> = DynamicValue<Int>(startValue)

    val testLithoView =
        lithoViewRule.render {
          Row(style = Style.width(100.px).height(100.px).backgroundColor(backgroundColorDV))
        }

    assertThat(testLithoView.lithoView.background).isInstanceOf(ColorDrawable::class.java)
    assertThat((testLithoView.lithoView.background as ColorDrawable).color).isEqualTo(Color.RED)

    backgroundColorDV.set(Color.WHITE)
    assertThat((testLithoView.lithoView.background as ColorDrawable).color).isEqualTo(Color.WHITE)

    backgroundColorDV.set(Color.TRANSPARENT)
    assertThat((testLithoView.lithoView.background as ColorDrawable).color)
        .isEqualTo(Color.TRANSPARENT)
  }

  @Test
  fun dynamic_alpha_whenSet_isRespected() {
    val alpha = 0.5f
    val alphaDV: DynamicValue<Float> = DynamicValue<Float>(alpha)

    val testLithoView =
        lithoViewRule.render { Row(style = Style.width(100.px).height(100.px).alpha(alphaDV)) }

    assertThat(testLithoView.lithoView.alpha).isEqualTo(alpha)

    alphaDV.set(1f)
    assertThat(testLithoView.lithoView.alpha).isEqualTo(1f)
    alphaDV.set(0.7f)
    assertThat(testLithoView.lithoView.alpha).isEqualTo(0.7f)
  }

  @Test
  fun dynamic_translation_whenSet_isRespected() {
    val translationXDV: DynamicValue<Float> = DynamicValue<Float>(10f)
    val translationYDV: DynamicValue<Float> = DynamicValue<Float>(10f)

    val testLithoView =
        lithoViewRule.render {
          Row(
              style =
                  Style.width(100.px)
                      .height(100.px)
                      .translationX(translationXDV)
                      .translationY(translationYDV))
        }

    assertThat(testLithoView.lithoView.translationX).isEqualTo(10f)
    assertThat(testLithoView.lithoView.translationY).isEqualTo(10f)

    translationXDV.set(-50f)
    translationYDV.set(20f)
    assertThat(testLithoView.lithoView.translationX).isEqualTo(-50f)
    assertThat(testLithoView.lithoView.translationY).isEqualTo(20f)

    translationXDV.set(35f)
    translationYDV.set(-75f)
    assertThat(testLithoView.lithoView.translationX).isEqualTo(35f)
    assertThat(testLithoView.lithoView.translationY).isEqualTo(-75f)
  }

  @Test
  fun dynamic_rotation_whenSet_isRespected() {
    val rotationDV: DynamicValue<Float> = DynamicValue<Float>(10f)

    val testLithoView =
        lithoViewRule.render {
          Row(style = Style.width(100.px).height(100.px).rotation(rotationDV))
        }

    assertThat(testLithoView.lithoView.rotation).isEqualTo(10f)

    rotationDV.set(-350f)
    assertThat(testLithoView.lithoView.rotation).isEqualTo(-350f)

    rotationDV.set(0.5f)
    assertThat(testLithoView.lithoView.rotation).isEqualTo(0.5f)
  }

  @Test
  fun dynamic_scale_whenSet_isRespected() {
    val scaleXDV: DynamicValue<Float> = DynamicValue<Float>(10f)
    val scaleYDV: DynamicValue<Float> = DynamicValue<Float>(10f)

    val testLithoView =
        lithoViewRule.render {
          Row(style = Style.width(100.px).height(100.px).scaleX(scaleXDV).scaleY(scaleYDV))
        }

    assertThat(testLithoView.lithoView.scaleX).isEqualTo(10f)
    assertThat(testLithoView.lithoView.scaleY).isEqualTo(10f)

    scaleXDV.set(-50f)
    scaleYDV.set(20f)
    assertThat(testLithoView.lithoView.scaleX).isEqualTo(-50f)
    assertThat(testLithoView.lithoView.scaleY).isEqualTo(20f)

    scaleXDV.set(0.5f)
    scaleYDV.set(0.7f)
    assertThat(testLithoView.lithoView.scaleX).isEqualTo(0.5f)
    assertThat(testLithoView.lithoView.scaleY).isEqualTo(0.7f)
  }
}
