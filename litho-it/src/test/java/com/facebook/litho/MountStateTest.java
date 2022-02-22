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

import static com.facebook.litho.SizeSpec.EXACTLY;
import static com.facebook.litho.SizeSpec.makeSizeSpec;
import static com.facebook.rendercore.MountState.ROOT_HOST_ID;
import static org.assertj.core.api.Assertions.assertThat;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import com.facebook.litho.config.ComponentsConfiguration;
import com.facebook.litho.config.TempComponentsConfigurations;
import com.facebook.litho.testing.LegacyLithoViewRule;
import com.facebook.litho.testing.Whitebox;
import com.facebook.litho.testing.testrunner.LithoTestRunner;
import com.facebook.litho.widget.DynamicPropsComponentTester;
import com.facebook.litho.widget.EmptyComponent;
import com.facebook.litho.widget.Image;
import com.facebook.litho.widget.Progress;
import com.facebook.litho.widget.SolidColor;
import com.facebook.litho.widget.TextInput;
import com.facebook.rendercore.MountDelegate;
import com.facebook.rendercore.RenderTree;
import com.facebook.rendercore.RenderTreeNode;
import com.facebook.yoga.YogaEdge;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(LithoTestRunner.class)
public class MountStateTest {

  public final @Rule LegacyLithoViewRule mLegacyLithoViewRule = new LegacyLithoViewRule();

  private ComponentContext mContext;

  @Before
  public void setup() {
    mContext = mLegacyLithoViewRule.getContext();
  }

  @Test
  public void testDetachLithoView_unbindComponentFromContent() {
    final Component child1 =
        DynamicPropsComponentTester.create(mContext).dynamicPropValue(1).build();

    final Component root =
        Column.create(mContext)
            .child(Wrapper.create(mContext).delegate(child1).widthPx(10).heightPx(10))
            .build();

    mLegacyLithoViewRule
        .setRoot(root)
        .attachToWindow()
        .setSizeSpecs(makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY))
        .measure()
        .layout();

    final DynamicPropsManager dynamicPropsManager =
        mLegacyLithoViewRule.getLithoView().getDynamicPropsManager();

    assertThat(dynamicPropsManager).isNotNull();
    assertThat(dynamicPropsManager.hasCachedContent(child1)).isTrue();

    mLegacyLithoViewRule.detachFromWindow();
    assertThat(dynamicPropsManager.hasCachedContent(child1)).isFalse();
  }

  @Test
  public void testUnbindMountItem_unbindComponentFromContent() {
    final Component child1 =
        DynamicPropsComponentTester.create(mContext).dynamicPropValue(1).build();

    final Component root =
        Column.create(mContext)
            .child(Wrapper.create(mContext).delegate(child1).widthPx(10).heightPx(10))
            .build();

    mLegacyLithoViewRule
        .setRoot(root)
        .attachToWindow()
        .setSizeSpecs(makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY))
        .measure()
        .layout();

    final DynamicPropsManager dynamicPropsManager =
        mLegacyLithoViewRule.getLithoView().getDynamicPropsManager();
    assertThat(dynamicPropsManager.hasCachedContent(child1)).isTrue();

    mLegacyLithoViewRule.setRoot(Column.create(mContext).build());
    assertThat(dynamicPropsManager.hasCachedContent(child1)).isFalse();
  }

  @Test
  public void onSetRootWithNoOutputsWithRenderCore_shouldSuccessfullyCompleteMount() {
    final boolean delegateToRenderCoreMount = ComponentsConfiguration.delegateToRenderCoreMount;

    ComponentsConfiguration.delegateToRenderCoreMount = true;

    final Component root =
        Wrapper.create(mContext)
            .delegate(SolidColor.create(mContext).color(Color.BLACK).build())
            .build();

    mLegacyLithoViewRule
        .setRoot(root)
        .attachToWindow()
        .setSizeSpecs(makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY))
        .measure()
        .layout();

    final Component emptyRoot = Wrapper.create(mContext).delegate(null).build();

    mLegacyLithoViewRule.setRoot(emptyRoot);

    ComponentsConfiguration.delegateToRenderCoreMount = delegateToRenderCoreMount;
  }

  @Test
  public void onSetRootWithSimilarComponent_MountContentShouldUsePools() {
    final boolean delegateToRenderCoreMount = ComponentsConfiguration.delegateToRenderCoreMount;

    ComponentsConfiguration.delegateToRenderCoreMount = true;

    final Component root =
        Column.create(mContext)
            .child(TextInput.create(mContext).widthDip(100).heightDip(100))
            .build();

    mLegacyLithoViewRule
        .setRoot(root)
        .attachToWindow()
        .setSizeSpecs(makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY))
        .measure()
        .layout();

    View view = mLegacyLithoViewRule.getLithoView().getChildAt(0);

    final Component newRoot =
        Row.create(mContext)
            .child(TextInput.create(mContext).initialText("testing").widthDip(120).heightDip(120))
            .build();

    mLegacyLithoViewRule
        .setRoot(newRoot)
        .setSizeSpecs(makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY));

    View newView = mLegacyLithoViewRule.getLithoView().getChildAt(0);

    assertThat(newView).isSameAs(view);

    ComponentsConfiguration.delegateToRenderCoreMount = delegateToRenderCoreMount;
  }

  @Test
  public void onSetRootWithDifferentComponent_MountContentPoolsShouldNoCollide() {
    final boolean delegateToRenderCoreMount = ComponentsConfiguration.delegateToRenderCoreMount;

    ComponentsConfiguration.delegateToRenderCoreMount = true;

    final Component root =
        Column.create(mContext)
            .child(TextInput.create(mContext).widthDip(100).heightDip(100))
            .build();

    mLegacyLithoViewRule
        .setRoot(root)
        .attachToWindow()
        .setSizeSpecs(makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY))
        .measure()
        .layout();

    final Component newRoot =
        Column.create(mContext)
            .child(Progress.create(mContext).widthDip(100).heightDip(100))
            .build();

    mLegacyLithoViewRule
        .setRoot(newRoot)
        .setSizeSpecs(makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY));

    ComponentsConfiguration.delegateToRenderCoreMount = delegateToRenderCoreMount;
  }

  @Test
  public void onSetRootWithNullComponentWithStatelessness_shouldMountWithoutCrashing() {
    TempComponentsConfigurations.setDelegateToRenderCoreMount(true);

    mLegacyLithoViewRule
        .attachToWindow()
        .setRoot(EmptyComponent.create(mLegacyLithoViewRule.getContext()))
        .setSizeSpecs(makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY))
        .measure()
        .layout();

    assertThat(mLegacyLithoViewRule.getCurrentRootNode()).isNull();
    assertThat(mLegacyLithoViewRule.getLithoView().getChildCount()).isEqualTo(0);

    final RenderTree tree = mLegacyLithoViewRule.getCommittedLayoutState().toRenderTree();

    assertThat(tree.getMountableOutputCount()).isEqualTo(1);
    assertThat(tree.getRoot()).isSameAs(tree.getRenderTreeNodeAtIndex(0));
    assertThat(tree.getRenderTreeNodeIndex(ROOT_HOST_ID)).isEqualTo(0);

    TempComponentsConfigurations.restoreDelegateToRenderCoreMount();
  }

  @Test
  public void mountingChildForUnmountedParentInRenderCore_shouldMountWithoutCrashing() {
    TempComponentsConfigurations.setDelegateToRenderCoreMount(true);
    TempComponentsConfigurations.setShouldAddHostViewForRootComponent(true);
    TempComponentsConfigurations.setEnsureParentMountedInRenderCoreMountState(true);

    final Component root =
        Row.create(mContext)
            .backgroundColor(Color.BLUE)
            .widthPx(20)
            .heightPx(20)
            .viewTag("root")
            .child(
                Row.create(mContext) // Parent that will be unmounted
                    .backgroundColor(Color.RED)
                    .widthPx(20)
                    .heightPx(20)
                    .viewTag("parent")
                    .border(
                        Border.create(mContext) // Drawable to be mounted after parent unmounts
                            .widthPx(YogaEdge.ALL, 2)
                            .color(YogaEdge.ALL, Color.YELLOW)
                            .build()))
            .build();

    mLegacyLithoViewRule
        .attachToWindow()
        .setRoot(root)
        .setSizeSpecs(makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY))
        .measure()
        .layout();

    final ComponentHost parentOfParent =
        (ComponentHost) mLegacyLithoViewRule.findViewWithTagOrNull("root");

    final RenderTreeNode parentNode = parentOfParent.getMountItemAt(0).getRenderTreeNode();

    final long parentId = parentNode.getRenderUnit().getId();
    final long childId = parentNode.getChildAt(0).getRenderUnit().getId();

    // Unmount the parent
    mLegacyLithoViewRule.getLithoView().getMountDelegateTarget().notifyUnmount(parentId);

    // Attempt to mount the child (border drawable)
    // If there is a problem, a crash will occur here.
    mLegacyLithoViewRule.getLithoView().getMountDelegateTarget().notifyMount(childId);

    TempComponentsConfigurations.restoreDelegateToRenderCoreMount();
    TempComponentsConfigurations.restoreShouldAddHostViewForRootComponent();
    TempComponentsConfigurations.restoreEnsureParentMountedInRenderCoreMountState();
  }

  @Test
  public void shouldUnregisterAllExtensions_whenUnmountAllItems() {
    TempComponentsConfigurations.setDelegateToRenderCoreMount(true);
    TempComponentsConfigurations.setEnsureParentMountedInRenderCoreMountState(true);

    final Component root =
        Row.create(mContext)
            .backgroundColor(Color.BLUE)
            .child(
                Image.create(mContext)
                    .drawable(new ColorDrawable(Color.RED))
                    .heightPx(100)
                    .widthPx(200))
            .build();

    mLegacyLithoViewRule
        .attachToWindow()
        .setRoot(root)
        .setSizeSpecs(makeSizeSpec(1000, EXACTLY), makeSizeSpec(1000, EXACTLY))
        .measure()
        .layout();

    final LithoView lithoView = mLegacyLithoViewRule.getLithoView();
    final MountDelegate mountDelegate = lithoView.getMountDelegateTarget().getMountDelegate();
    LithoHostListenerCoordinator coordinator;

    coordinator = Whitebox.getInternalState(lithoView, "mLithoHostListenerCoordinator");

    assertThat(coordinator).isNotNull();
    assertThat(coordinator.getVisibilityExtensionState()).isNotNull();
    assertThat(coordinator.getIncrementalMountExtensionState()).isNotNull();

    assertThat(mountDelegate).isNotNull();
    assertThat(mountDelegate.getExtensionStates()).isNotEmpty();

    // Unmount the parent
    mLegacyLithoViewRule.getLithoView().unmountAllItems();

    coordinator = Whitebox.getInternalState(lithoView, "mLithoHostListenerCoordinator");

    assertThat(coordinator).isNull();
    assertThat(mountDelegate).isNotNull();
    assertThat(mountDelegate.getExtensionStates()).isEmpty();

    TempComponentsConfigurations.restoreDelegateToRenderCoreMount();
    TempComponentsConfigurations.restoreEnsureParentMountedInRenderCoreMountState();
  }
}
