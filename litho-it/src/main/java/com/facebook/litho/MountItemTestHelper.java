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

import android.graphics.Rect;
import com.facebook.rendercore.MountItem;
import com.facebook.rendercore.RenderTreeNode;

public class MountItemTestHelper {
  public static MountItem create(
      Component component,
      String globalKey,
      ComponentHost host,
      Object content,
      NodeInfo info,
      ViewNodeInfo viewInfo,
      Rect bounds,
      int hostTranslationX,
      int hostTranslationY,
      int flags,
      long hostMarker,
      int importantForAccessibility,
      int orientation,
      TransitionId transitionId) {
    LithoRenderUnit unit =
        MountSpecLithoRenderUnit.create(
            0,
            component,
            null,
            info,
            viewInfo,
            flags,
            importantForAccessibility,
            LayoutOutput.STATE_UNKNOWN);
    RenderTreeNode node =
        RenderTreeNodeUtils.create(unit, bounds != null ? bounds : new Rect(), null, null);
    MountItem item = new MountItem(node, host, content);
    item.setMountData(new LithoMountData(content));
    return item;
  }
}
