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

import static com.facebook.rendercore.RenderUnit.Extension.extension;

import android.content.Context;
import androidx.annotation.Nullable;
import com.facebook.infer.annotation.Nullsafe;
import java.util.List;

@Nullsafe(Nullsafe.Mode.LOCAL)
public class MountableLithoRenderUnit extends LithoRenderUnit {

  private final Mountable<?> mMountable;

  private MountableLithoRenderUnit(
      final long id,
      final LayoutOutput output,
      final Mountable mountable,
      final @Nullable ComponentContext context) {
    super(id, output, mountable.getRenderType(), context);
    this.mMountable = mountable;

    addMountUnmountExtentions(mMountable);
  }

  private void addMountUnmountExtentions(Mountable mountable) {
    List<Binder> binders = mountable.getBinders();
    if (binders != null) {
      for (Binder binder : binders) {
        addMountUnmountExtensions(extension(mMountable, binder));
      }
    }
  }

  public static MountableLithoRenderUnit create(
      final long id,
      final Component component,
      final @Nullable ComponentContext context,
      final @Nullable NodeInfo nodeInfo,
      final @Nullable ViewNodeInfo viewNodeInfo,
      final int flags,
      final int importantForAccessibility,
      final @LayoutOutput.UpdateState int updateState,
      final Mountable mountable) {
    final LayoutOutput output =
        new LayoutOutput(
            component, nodeInfo, viewNodeInfo, flags, importantForAccessibility, updateState);

    return new MountableLithoRenderUnit(id, output, mountable, context);
  }

  @Override
  public Object createContent(Context c) {
    return mMountable.createContent(c);
  }

  @Override
  protected Class getDescription() {
    return mMountable.getClass();
  }
}
