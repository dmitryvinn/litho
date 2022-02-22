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

import android.graphics.PathEffect;
import androidx.annotation.Nullable;
import com.facebook.litho.annotations.OnCreateLayoutWithSizeSpec;
import com.facebook.yoga.YogaNode;

/**
 * This class is a placeholder for the unresolved layout and result of a {@link Component}s which
 * implement the {@link OnCreateLayoutWithSizeSpec}.The {@link TreeProps}, padding and border width
 * properties and held separately so that they can be copied into the actual nested tree layout
 * before measuring it.
 */
public class NestedTreeHolder extends LithoNode {

  final @Nullable TreeProps mPendingTreeProps;

  @Nullable int[] mNestedBorderEdges;
  @Nullable Edges mNestedTreePadding;
  @Nullable boolean[] mNestedIsPaddingPercentage;

  protected NestedTreeHolder(ComponentContext context, @Nullable TreeProps props) {
    super(context);
    mPendingTreeProps = TreeProps.copy(props);
  }

  public @Nullable TreeProps getPendingTreeProps() {
    return mPendingTreeProps;
  }

  @Override
  public void border(int[] widths, int[] colors, float[] radii, PathEffect effect) {
    mNestedBorderEdges = new int[Border.EDGE_COUNT];
    System.arraycopy(widths, 0, mNestedBorderEdges, 0, mNestedBorderEdges.length);
    System.arraycopy(colors, 0, mBorderColors, 0, mBorderColors.length);
    System.arraycopy(radii, 0, mBorderRadius, 0, mBorderRadius.length);
    mBorderPathEffect = effect;
  }

  @Override
  protected NestedTreeYogaLayoutProps createYogaNodeWriter(YogaNode node) {
    return new NestedTreeYogaLayoutProps(node);
  }

  @Override
  NestedTreeYogaLayoutProps writeToYogaNode(YogaNode node) {
    NestedTreeYogaLayoutProps writer = (NestedTreeYogaLayoutProps) super.writeToYogaNode(node);
    mNestedBorderEdges = writer.getBorderWidth();
    mNestedTreePadding = writer.getPadding();
    mNestedIsPaddingPercentage = writer.getIsPaddingPercentage();

    return writer;
  }

  @Override
  NestedTreeHolderResult createLayoutResult(
      final LayoutStateContext context,
      final YogaNode node,
      final @Nullable LithoLayoutResult parent) {
    return new NestedTreeHolderResult(context, getTailComponentContext(), this, node, parent);
  }

  public void copyInto(LithoNode target) {
    // Defer copying, and set this NestedTreeHolder on the target. The props will be
    // transferred to the nested result during layout calculation.
    target.setNestedTreeHolder(this);
  }

  public void transferInto(LithoNode target) {
    if (mNodeInfo != null) {
      target.applyNodeInfo(mNodeInfo);
    }
    if (target.isImportantForAccessibilityIsSet()) {
      target.importantForAccessibility(mImportantForAccessibility);
    }
    if ((mPrivateFlags & PFLAG_DUPLICATE_PARENT_STATE_IS_SET) != 0L) {
      target.duplicateParentState(mDuplicateParentState);
    }
    if ((mPrivateFlags & PFLAG_DUPLICATE_CHILDREN_STATES_IS_SET) != 0L) {
      target.duplicateChildrenStates(mDuplicateChildrenStates);
    }
    if ((mPrivateFlags & PFLAG_BACKGROUND_IS_SET) != 0L) {
      target.background(mBackground);
    }
    if ((mPrivateFlags & PFLAG_FOREGROUND_IS_SET) != 0L) {
      target.foreground(mForeground);
    }
    if (mForceViewWrapping) {
      target.wrapInView();
    }
    if ((mPrivateFlags & PFLAG_VISIBLE_HANDLER_IS_SET) != 0L) {
      target.visibleHandler(mVisibleHandler);
    }
    if ((mPrivateFlags & PFLAG_FOCUSED_HANDLER_IS_SET) != 0L) {
      target.focusedHandler(mFocusedHandler);
    }
    if ((mPrivateFlags & PFLAG_FULL_IMPRESSION_HANDLER_IS_SET) != 0L) {
      target.fullImpressionHandler(mFullImpressionHandler);
    }
    if ((mPrivateFlags & PFLAG_INVISIBLE_HANDLER_IS_SET) != 0L) {
      target.invisibleHandler(mInvisibleHandler);
    }
    if ((mPrivateFlags & PFLAG_UNFOCUSED_HANDLER_IS_SET) != 0L) {
      target.unfocusedHandler(mUnfocusedHandler);
    }
    if ((mPrivateFlags & PFLAG_VISIBLE_RECT_CHANGED_HANDLER_IS_SET) != 0L) {
      target.visibilityChangedHandler(mVisibilityChangedHandler);
    }
    if (mTestKey != null) {
      target.testKey(mTestKey);
    }
    if (mNestedBorderEdges != null) {
      target.border(mNestedBorderEdges, mBorderColors, mBorderRadius, mBorderPathEffect);
    }
    if ((mPrivateFlags & PFLAG_TRANSITION_KEY_IS_SET) != 0L) {
      target.transitionKey(mTransitionKey, mTransitionOwnerKey);
    }
    if ((mPrivateFlags & PFLAG_TRANSITION_KEY_TYPE_IS_SET) != 0L) {
      target.transitionKeyType(mTransitionKeyType);
    }
    if (mVisibleHeightRatio != 0) {
      target.visibleHeightRatio(mVisibleHeightRatio);
    }
    if (mVisibleWidthRatio != 0) {
      target.visibleWidthRatio(mVisibleWidthRatio);
    }
    if ((mPrivateFlags & PFLAG_STATE_LIST_ANIMATOR_SET) != 0L) {
      target.stateListAnimator(mStateListAnimator);
    }
    if ((mPrivateFlags & PFLAG_STATE_LIST_ANIMATOR_RES_SET) != 0L) {
      target.stateListAnimatorRes(mStateListAnimatorRes);
    }
    if (mLayerType != LayerType.LAYER_TYPE_NOT_SET) {
      target.layerType(mLayerType, mLayerPaint);
    }

    target.setNestedPadding(mNestedTreePadding, mNestedIsPaddingPercentage);
  }
}
