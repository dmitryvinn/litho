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

package com.facebook.litho;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.facebook.litho.Column.create;
import static com.facebook.litho.it.R.style;
import static com.facebook.yoga.YogaEdge.ALL;
import static com.facebook.yoga.YogaEdge.BOTTOM;
import static com.facebook.yoga.YogaEdge.LEFT;
import static com.facebook.yoga.YogaEdge.RIGHT;
import static com.facebook.yoga.YogaEdge.TOP;
import static org.assertj.core.api.Assertions.assertThat;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.ContextThemeWrapper;
import android.view.View;
import com.facebook.litho.config.ComponentsConfiguration;
import com.facebook.litho.it.R;
import com.facebook.litho.testing.LegacyLithoViewRule;
import com.facebook.litho.testing.TestViewComponent;
import com.facebook.litho.testing.ViewGroupWithLithoViewChildren;
import com.facebook.litho.testing.inlinelayoutspec.InlineLayoutSpec;
import com.facebook.litho.testing.testrunner.LithoTestRunner;
import com.facebook.litho.widget.MountSpecLifecycleTester;
import com.facebook.litho.widget.SolidColor;
import com.facebook.litho.widget.Text;
import com.facebook.litho.widget.TextInput;
import com.facebook.rendercore.MountItem;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(LithoTestRunner.class)
public class MountStateViewTest {

  public final @Rule LegacyLithoViewRule mLegacyLithoViewRule = new LegacyLithoViewRule();

  private ComponentContext mContext;

  @Before
  public void setup() {
    mContext = mLegacyLithoViewRule.getContext();
  }

  @Test
  public void testViewPaddingAndBackground() {
    final int color = 0xFFFF0000;
    final InlineLayoutSpec component =
        new InlineLayoutSpec() {
          @Override
          protected Component onCreateLayout(ComponentContext c) {
            return create(c)
                .child(
                    TextInput.create(c)
                        .paddingPx(LEFT, 5)
                        .paddingPx(TOP, 6)
                        .paddingPx(RIGHT, 7)
                        .paddingPx(BOTTOM, 8)
                        .backgroundColor(color))
                .build();
          }
        };
    mLegacyLithoViewRule.setRoot(component);
    mLegacyLithoViewRule.attachToWindow().measure().layout();
    final LithoView lithoView = mLegacyLithoViewRule.getLithoView();

    final View child = lithoView.getChildAt(0);
    Drawable background = child.getBackground();

    assertThat(child.getPaddingLeft()).isEqualTo(5);
    assertThat(child.getPaddingTop()).isEqualTo(6);
    assertThat(child.getPaddingRight()).isEqualTo(7);
    assertThat(child.getPaddingBottom()).isEqualTo(8);
    assertThat(background).isInstanceOf(ColorDrawable.class);
    assertThat(((ColorDrawable) background).getColor()).isEqualTo(color);
  }

  @Test
  public void testSettingCustomPaddingOverridesDefaultBackgroundPadding() {
    final ComponentContext c =
        new ComponentContext(
            new ContextThemeWrapper(
                getApplicationContext(), style.TestTheme_BackgroundWithPadding));

    final Component component = TextInput.create(c).paddingPx(ALL, 9).build();
    mLegacyLithoViewRule.useContext(c);
    mLegacyLithoViewRule.attachToWindow().setRoot(component).measure().layout();
    final LithoView lithoView = mLegacyLithoViewRule.getLithoView();

    final View child = lithoView.getChildAt(0);

    assertThat(child.getPaddingLeft()).isEqualTo(9);
    assertThat(child.getPaddingTop()).isEqualTo(9);
    assertThat(child.getPaddingRight()).isEqualTo(9);
    assertThat(child.getPaddingBottom()).isEqualTo(9);
  }

  @Test
  public void testSettingOneSidePaddingClearsTheRest() {
    final ComponentContext c =
        new ComponentContext(
            new ContextThemeWrapper(
                getApplicationContext(), style.TestTheme_BackgroundWithPadding));

    final Component component = TextInput.create(c).paddingPx(LEFT, 12).build();
    mLegacyLithoViewRule.useContext(c);
    mLegacyLithoViewRule.attachToWindow().setRoot(component).measure().layout();
    final LithoView lithoView = mLegacyLithoViewRule.getLithoView();

    final View child = lithoView.getChildAt(0);

    assertThat(child.getPaddingLeft()).isEqualTo(12);
    assertThat(child.getPaddingTop()).isZero();
    assertThat(child.getPaddingRight()).isZero();
    assertThat(child.getPaddingBottom()).isZero();
  }

  @Test
  public void testComponentDeepUnmount() {
    final LifecycleTracker lifecycleTracker = new LifecycleTracker();
    final Component testComponent =
        MountSpecLifecycleTester.create(mContext).lifecycleTracker(lifecycleTracker).build();

    final Component mountedTestComponent =
        new InlineLayoutSpec() {
          @Override
          protected Component onCreateLayout(ComponentContext c) {
            return Column.create(c)
                .child(Wrapper.create(c).delegate(testComponent).widthPx(10).heightPx(10))
                .build();
          }
        };

    mLegacyLithoViewRule.setRoot(mountedTestComponent).attachToWindow().measure().layout();
    assertThat(lifecycleTracker.isMounted()).isTrue();

    final ViewGroupWithLithoViewChildren viewGroup =
        new ViewGroupWithLithoViewChildren(mContext.getAndroidContext());
    final View child = mLegacyLithoViewRule.getLithoView();
    viewGroup.addView(child);

    mLegacyLithoViewRule.setRoot(TestViewComponent.create(mContext).testView(viewGroup).build());
    assertThat(lifecycleTracker.isMounted()).isTrue();

    mLegacyLithoViewRule.getLithoView().unmountAllItems();
    assertThat(lifecycleTracker.isMounted()).isFalse();
  }

  @Test
  public void onMountedContentSize_shouldBeEqualToLayoutOutputSize() {
    final Component component =
        Column.create(mContext)
            .child(TextInput.create(mContext).widthPx(100).heightPx(100))
            .child(SolidColor.create(mContext).color(Color.BLACK).widthPx(100).heightPx(100))
            .child(
                Text.create(mContext)
                    .text("hello world")
                    .widthPx(80)
                    .heightPx(80)
                    .paddingPx(ALL, 10)
                    .marginPx(ALL, 10))
            .build();

    mLegacyLithoViewRule.setRoot(component);
    mLegacyLithoViewRule.attachToWindow().measure().layout();

    final LithoView root = mLegacyLithoViewRule.getLithoView();

    final View view = root.getChildAt(0);
    final Rect viewBounds =
        root.getMountItemAt(0).getRenderTreeNode().getAbsoluteBounds(new Rect());

    assertThat(view.getWidth()).isEqualTo(viewBounds.width());
    assertThat(view.getHeight()).isEqualTo(viewBounds.height());

    final MountItem item1 = root.getMountItemAt(1);
    final Rect drawableOutputBounds = item1.getRenderTreeNode().getAbsoluteBounds(new Rect());
    final Rect drawablesActualBounds = ((Drawable) item1.getContent()).getBounds();

    assertThat(drawablesActualBounds.width()).isEqualTo(drawableOutputBounds.width());
    assertThat(drawablesActualBounds.height()).isEqualTo(drawableOutputBounds.height());

    final MountItem item2 = root.getMountItemAt(2);
    final Rect textOutputBounds = item2.getRenderTreeNode().getAbsoluteBounds(new Rect());
    final Rect textActualBounds = ((Drawable) item2.getContent()).getBounds();

    assertThat(textActualBounds.width()).isEqualTo(textOutputBounds.width());
    assertThat(textActualBounds.height()).isEqualTo(textOutputBounds.height());
  }

  @Test
  public void onMountContentWithPadded9PatchDrawable_shouldNotSetPaddingOnHost() {
    final boolean cachedValue = ComponentsConfiguration.shouldDisableBgFgOutputs;
    ComponentsConfiguration.shouldDisableBgFgOutputs = true;

    final Component component =
        Column.create(mContext)
            .backgroundRes(R.drawable.background_with_padding)
            .child(Text.create(mContext).text("hello world").textSizeSp(20))
            .build();

    mLegacyLithoViewRule.attachToWindow().setRoot(component).measure().layout();

    assertThat(mLegacyLithoViewRule.getLithoView().getPaddingTop()).isEqualTo(0);
    assertThat(mLegacyLithoViewRule.getLithoView().getPaddingRight()).isEqualTo(0);
    assertThat(mLegacyLithoViewRule.getLithoView().getPaddingBottom()).isEqualTo(0);
    assertThat(mLegacyLithoViewRule.getLithoView().getPaddingLeft()).isEqualTo(0);

    ComponentsConfiguration.shouldDisableBgFgOutputs = cachedValue;
  }
}
