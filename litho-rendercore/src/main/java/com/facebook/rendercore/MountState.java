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

package com.facebook.rendercore;

import static com.facebook.rendercore.extensions.RenderCoreExtension.shouldUpdate;

import android.content.Context;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.collection.LongSparseArray;
import androidx.core.util.Pair;
import com.facebook.rendercore.extensions.ExtensionState;
import com.facebook.rendercore.extensions.MountExtension;
import com.facebook.rendercore.extensions.RenderCoreExtension;
import com.facebook.rendercore.utils.BoundsUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MountState implements MountDelegateTarget {

  public static final long ROOT_HOST_ID = 0L;
  private static final String TAG = "MountState";

  private final LongSparseArray<MountItem> mIdToMountedItemMap;
  private final Context mContext;
  private final Host mRootHost;

  private boolean mIsMounting;
  private boolean mNeedsRemount;
  private RenderTree mRenderTree;
  private @Nullable MountDelegate mMountDelegate;
  private @Nullable UnmountDelegateExtension mUnmountDelegateExtension;

  private boolean mEnsureParentMounted = true;

  public MountState(Host rootHost) {
    mIdToMountedItemMap = new LongSparseArray<>();
    mContext = rootHost.getContext();
    mRootHost = rootHost;
  }

  public void setEnsureParentMounted(boolean ensureParentMounted) {
    mEnsureParentMounted = ensureParentMounted;
  }

  /**
   * True if we have manually unmounted content (e.g. via unmountAllItems) which means that while we
   * may not have a new RenderTree, the mounted content does not match what the viewport for the
   * LithoView may be.
   */
  @Override
  public boolean needsRemount() {
    return mNeedsRemount;
  }

  @Override
  public void notifyMount(long id) {
    if (mIdToMountedItemMap.get(id) != null) {
      return;
    }

    final int position = mRenderTree.getRenderTreeNodeIndex(id);
    final RenderTreeNode node = mRenderTree.getRenderTreeNodeAtIndex(position);
    mountRenderUnit(node, null);
  }

  @Override
  public void notifyUnmount(long id) {
    if (mIdToMountedItemMap == null) {
      return;
    }

    final MountItem mountItem = mIdToMountedItemMap.get(id);
    if (mountItem != null) {
      unmountItemRecursively(mountItem.getRenderTreeNode());
    }
  }

  /**
   * Mount the layoutState on the pre-set HostView.
   *
   * @param renderTree a new {@link RenderTree} to mount
   */
  @Override
  public void mount(RenderTree renderTree) {
    if (renderTree == null) {
      throw new IllegalStateException("Trying to mount a null RenderTreeNode");
    }

    if (mIsMounting) {
      throw new IllegalStateException("Trying to mount while already mounting!");
    }

    final RenderTree previousRenderTree = mRenderTree;

    if (!updateRenderTree(renderTree)) {
      return;
    }

    RenderCoreSystrace.beginSection("MountState.mount");

    mIsMounting = true;

    RenderCoreSystrace.beginSection("RenderCoreExtension.beforeMount");
    RenderCoreExtension.beforeMount(mRootHost, mMountDelegate, mRenderTree.getExtensionResults());
    RenderCoreSystrace.endSection();

    RenderCoreSystrace.beginSection("MountState.prepareMount");
    prepareMount(previousRenderTree);
    RenderCoreSystrace.endSection();

    // TODO: Remove this additional logging when root cause of crash in mountRenderUnit is found.
    // We only want to collect logs when we're not ensuring the parent is mounted. When false,
    // we will throw an exception that contains these logs. The StringBuilder is not needed when
    // mEnsureParentMount is true.
    @Nullable final StringBuilder mountLoopLogBuilder;
    if (!mEnsureParentMounted) {
      mountLoopLogBuilder = new StringBuilder();
      mountLoopLogBuilder.append("Start of mount loop log:\n");
    } else {
      mountLoopLogBuilder = null;
    }

    // Starting from 1 as the RenderTreeNode in position 0 always represents the root which is
    // handled in prepareMount()
    for (int i = 1, size = renderTree.getMountableOutputCount(); i < size; i++) {
      final RenderTreeNode renderTreeNode = renderTree.getRenderTreeNodeAtIndex(i);

      final boolean isMountable = isMountable(renderTreeNode, i);
      final MountItem currentMountItem =
          mIdToMountedItemMap.get(renderTreeNode.getRenderUnit().getId());
      boolean isMounted = currentMountItem != null;

      // There is a bug (T99579422) happening where we try to incorrectly update an already mounted
      // render unit.
      // TODO: T101249557
      if (isMounted) {
        final RenderUnit currentRenderUnit = currentMountItem.getRenderUnit();
        boolean needsRecovery = false;
        // The old render unit we try to update is the root host which should not be updated (that's
        // why we start from index 1).
        if (currentRenderUnit.getId() != renderTreeNode.getRenderUnit().getId()) {
          needsRecovery = true;
          ErrorReporter.getInstance()
              .report(
                  LogLevel.ERROR,
                  TAG,
                  "The current render unit id does not match the new one. "
                      + " index: "
                      + i
                      + " mountableOutputCounts: "
                      + renderTree.getMountableOutputCount()
                      + " currentRenderUnitId: "
                      + currentRenderUnit.getId()
                      + " newRenderUnitId: "
                      + renderTreeNode.getRenderUnit().getId(),
                  null,
                  0,
                  null);
        }

        // The new render unit is not the same type as the old one.
        if (!currentRenderUnit
            .getRenderContentType()
            .equals(renderTreeNode.getRenderUnit().getRenderContentType())) {
          needsRecovery = true;
          ErrorReporter.getInstance()
              .report(
                  LogLevel.ERROR,
                  TAG,
                  "Trying to update a MountItem with different ContentType. "
                      + "index: "
                      + i
                      + " currentRenderUnitId: "
                      + currentRenderUnit.getId()
                      + " newRenderUnitId: "
                      + renderTreeNode.getRenderUnit().getId()
                      + " currentRenderUnitContentType: "
                      + currentRenderUnit.getRenderContentType()
                      + " newRenderUnitContentType: "
                      + renderTreeNode.getRenderUnit().getRenderContentType(),
                  null,
                  0,
                  null);
        }
        if (needsRecovery) {
          recreateMountedItemMap(previousRenderTree);
          // reset the loop to start over.
          i = 1;
          continue;
        }
      }

      if (!mEnsureParentMounted) {
        mountLoopLogBuilder.append(
            String.format(
                Locale.US,
                "Processing index %d: isMountable = %b, isMounted = %b\n",
                i,
                isMountable,
                isMounted));
      }

      if (!isMountable) {
        if (isMounted) {
          unmountItemRecursively(renderTreeNode);
        }
      } else if (!isMounted) {
        RenderCoreSystrace.beginSection(
            "MountItem: ", renderTreeNode.getRenderUnit().getDescription());
        mountRenderUnit(renderTreeNode, mountLoopLogBuilder);
        RenderCoreSystrace.endSection();
      } else {
        updateMountItemIfNeeded(mMountDelegate, mContext, renderTreeNode, currentMountItem);
      }
    }

    mNeedsRemount = false;
    mIsMounting = false;
    RenderCoreSystrace.endSection();

    RenderCoreSystrace.beginSection("RenderCoreExtension.afterMount");
    RenderCoreExtension.afterMount(mMountDelegate);
    RenderCoreSystrace.endSection();
  }

  /**
   * This method will unmount everything and recreate the mIdToMountedItemMap.
   *
   * @param previousRenderTree
   */
  private void recreateMountedItemMap(RenderTree previousRenderTree) {
    // We keep a pointer to the rootHost.
    MountItem rootHost = null;
    final long[] keysToUnmount = new long[mIdToMountedItemMap.size()];
    // We unmount all everything but the root host.
    for (int j = 0, mountedItems = mIdToMountedItemMap.size(); j < mountedItems; j++) {
      keysToUnmount[j] = mIdToMountedItemMap.keyAt(j);
    }
    for (long keyAt : keysToUnmount) {
      final MountItem mountItem = mIdToMountedItemMap.get(keyAt);
      if (mountItem != null) {
        if (mountItem.getRenderUnit().getId() == ROOT_HOST_ID) {
          rootHost = mountItem;
          mIdToMountedItemMap.remove(keyAt);
        } else if (mountItem.getRenderUnit().getId() != keyAt) {
          // This checks if the item was in the wrong position in the map. If it was we need to
          // unmount that item.
          unmountItemRecursively(
              previousRenderTree.getRenderTreeNodeAtIndex(
                  previousRenderTree.getRenderTreeNodeIndex(keyAt)));
        } else {
          unmountItemRecursively(mountItem.getRenderTreeNode());
        }
      }
    }
    mIdToMountedItemMap.put(ROOT_HOST_ID, rootHost);
  }

  @Override
  public void unmountAllItems() {
    if (mRenderTree == null) {
      unregisterAllExtensions();
      return;
    }
    // Let's unmount all the content from the Root host. Everything else will be recursively
    // unmounted from there.
    final RenderTreeNode rootRenderTreeNode = mRenderTree.getRoot();

    for (int i = 0; i < rootRenderTreeNode.getChildrenCount(); i++) {
      unmountItemRecursively(rootRenderTreeNode.getChildAt(i));
    }

    // Let's unbind and unmount the root host.
    unmountRootItem();

    unregisterAllExtensions();

    mNeedsRemount = true;
  }

  @Override
  public boolean isRootItem(int position) {
    if (mRenderTree == null || position >= mRenderTree.getMountableOutputCount()) {
      return false;
    }
    final RenderUnit renderUnit = mRenderTree.getRenderTreeNodeAtIndex(position).getRenderUnit();
    final MountItem mountItem = mIdToMountedItemMap.get(renderUnit.getId());
    if (mountItem == null) {
      return false;
    }

    return mountItem == mIdToMountedItemMap.get(ROOT_HOST_ID);
  }

  @Override
  public @Nullable MountItem getRootItem() {
    return mIdToMountedItemMap != null ? mIdToMountedItemMap.get(ROOT_HOST_ID) : null;
  }

  @Override
  public @Nullable Object getContentAt(int position) {
    if (mRenderTree == null || position >= mRenderTree.getMountableOutputCount()) {
      return null;
    }

    final MountItem mountItem =
        mIdToMountedItemMap.get(
            mRenderTree.getRenderTreeNodeAtIndex(position).getRenderUnit().getId());
    if (mountItem == null) {
      return null;
    }

    return mountItem.getContent();
  }

  @Override
  public @Nullable Object getContentById(long id) {
    if (mIdToMountedItemMap == null) {
      return null;
    }

    final MountItem mountItem = mIdToMountedItemMap.get(id);

    if (mountItem == null) {
      return null;
    }

    return mountItem.getContent();
  }

  /**
   * @param mountExtension
   * @deprecated Only used for Litho's integration. Marked for removal.
   */
  @Deprecated
  @Override
  public ExtensionState registerMountExtension(MountExtension mountExtension) {
    if (mMountDelegate == null) {
      mMountDelegate = new MountDelegate(this);
    }
    return mMountDelegate.registerMountExtension(mountExtension);
  }

  @Override
  public ArrayList<Host> getHosts() {
    final ArrayList<Host> hosts = new ArrayList<>();
    for (int i = 0, size = mIdToMountedItemMap.size(); i < size; i++) {
      final MountItem item = mIdToMountedItemMap.valueAt(i);
      final Object content = item.getContent();
      if (content instanceof Host) {
        hosts.add((Host) content);
      }
    }

    return hosts;
  }

  @Override
  public @Nullable MountItem getMountItemAt(int position) {
    return mIdToMountedItemMap.get(
        mRenderTree.getRenderTreeNodeAtIndex(position).getRenderUnit().getId());
  }

  @Override
  public int getMountItemCount() {
    return mIdToMountedItemMap.size();
  }

  @Override
  public int getRenderUnitCount() {
    return mRenderTree == null ? 0 : mRenderTree.getMountableOutputCount();
  }

  @Override
  public void setUnmountDelegateExtension(UnmountDelegateExtension unmountDelegateExtension) {
    mUnmountDelegateExtension = unmountDelegateExtension;
  }

  @Override
  public void removeUnmountDelegateExtension() {
    mUnmountDelegateExtension = null;
  }

  @Nullable
  @Override
  public MountDelegate getMountDelegate() {
    return mMountDelegate;
  }

  /**
   * This is called when the {@link MountItem}s mounted on this {@link MountState} need to be
   * re-bound with the same RenderUnit. This happens when a detach/attach happens on the root {@link
   * Host} that owns the MountState.
   */
  @Override
  public void attach() {
    if (mRenderTree == null) {
      return;
    }

    RenderCoreSystrace.beginSection("MountState.bind");

    for (int i = 0, size = mRenderTree.getMountableOutputCount(); i < size; i++) {
      final RenderUnit renderUnit = mRenderTree.getRenderTreeNodeAtIndex(i).getRenderUnit();
      final MountItem mountItem = mIdToMountedItemMap.get(renderUnit.getId());
      if (mountItem == null || mountItem.isBound()) {
        continue;
      }

      final Object content = mountItem.getContent();
      bindRenderUnitToContent(mMountDelegate, mContext, mountItem);

      if (content instanceof View
          && !(content instanceof Host)
          && ((View) content).isLayoutRequested()) {
        final View view = (View) content;

        BoundsUtils.applyBoundsToMountContent(mountItem.getRenderTreeNode(), view, true);
      }
    }

    RenderCoreSystrace.endSection();
  }

  /** Unbinds all the MountItems currently mounted on this MountState. */
  @Override
  public void detach() {
    if (mRenderTree == null) {
      return;
    }

    final boolean isTracing = RenderCoreSystrace.isEnabled();
    if (isTracing) {
      RenderCoreSystrace.beginSection("MountState.unbind");
      RenderCoreSystrace.beginSection("MountState.unbindAllContent");
    }

    for (int i = 0, size = mRenderTree.getMountableOutputCount(); i < size; i++) {
      final RenderUnit renderUnit = mRenderTree.getRenderTreeNodeAtIndex(i).getRenderUnit();
      final MountItem mountItem = mIdToMountedItemMap.get(renderUnit.getId());

      if (mountItem == null || !mountItem.isBound()) {
        continue;
      }

      unbindRenderUnitFromContent(mMountDelegate, mContext, mountItem);
    }

    if (isTracing) {
      RenderCoreSystrace.endSection();
      RenderCoreSystrace.beginSection("MountState.unbindExtensions");
    }

    if (mMountDelegate != null) {
      mMountDelegate.unBind();
    }

    if (isTracing) {
      RenderCoreSystrace.endSection();
      RenderCoreSystrace.endSection();
    }
  }

  @Nullable
  RenderTree getRenderTree() {
    return mRenderTree;
  }

  private boolean isMountable(RenderTreeNode renderTreeNode, int index) {
    return mMountDelegate == null || mMountDelegate.maybeLockForMount(renderTreeNode, index);
  }

  private static void updateBoundsForMountedRenderTreeNode(
      RenderTreeNode renderTreeNode, MountItem item, @Nullable MountDelegate mountDelegate) {
    // MountState should never update the bounds of the top-level host as this
    // should be done by the ViewGroup containing the LithoView.
    if (renderTreeNode.getRenderUnit().getId() == ROOT_HOST_ID) {
      return;
    }

    final Object content = item.getContent();
    final boolean forceTraversal = content instanceof View && ((View) content).isLayoutRequested();

    BoundsUtils.applyBoundsToMountContent(
        item.getRenderTreeNode(), item.getContent(), forceTraversal /* force */);

    if (mountDelegate != null) {
      mountDelegate.onBoundsAppliedToItem(renderTreeNode, item.getContent());
    }
  }

  /** Updates the extensions of this {@link MountState} from the new {@link RenderTree}. */
  private boolean updateRenderTree(RenderTree renderTree) {
    // If the trees are same or if no remount is required, then no update is required.
    if (renderTree == mRenderTree && !mNeedsRemount) {
      return false;
    }

    // If the extensions have changed, un-register the current and register the new extensions.
    if (mRenderTree == null || mNeedsRemount) {
      addExtensions(renderTree.getExtensionResults());
    } else if (shouldUpdate(mRenderTree.getExtensionResults(), renderTree.getExtensionResults())) {
      unregisterAllExtensions();
      addExtensions(renderTree.getExtensionResults());
    }

    // Update the current render tree.
    mRenderTree = renderTree;

    return true;
  }

  /**
   * Prepare the {@link MountState} to mount a new {@link RenderTree}.
   *
   * @param previousRenderTree
   */
  private void prepareMount(@Nullable RenderTree previousRenderTree) {
    unmountOrMoveOldItems(previousRenderTree);

    final MountItem rootItem = mIdToMountedItemMap.get(ROOT_HOST_ID);
    final RenderTreeNode rootNode = mRenderTree.getRenderTreeNodeAtIndex(0);

    // If root mount item is null then mounting root node for the first time.
    if (rootItem == null) {
      mountRootItem(rootNode);
    } else {
      // If root mount item is present then update it.
      updateMountItemIfNeeded(mMountDelegate, mContext, rootNode, rootItem);
    }
  }

  /**
   * Go over all the mounted items from the leaves to the root and unmount only the items that are
   * not present in the new LayoutOutputs. If an item is still present but in a new position move
   * the item inside its host. The condition where an item changed host doesn't need any special
   * treatment here since we mark them as removed and re-added when calculating the new
   * LayoutOutputs
   */
  private void unmountOrMoveOldItems(@Nullable RenderTree previousRenderTree) {
    if (mRenderTree == null || previousRenderTree == null) {
      return;
    }

    RenderCoreSystrace.beginSection("unmountOrMoveOldItems");

    // Traversing from the beginning since mRenderUnitIds unmounting won't remove entries there
    // but only from mIndexToMountedItemMap. If an host changes we're going to unmount it and
    // recursively
    // all its mounted children.
    for (int i = 0; i < previousRenderTree.getMountableOutputCount(); i++) {
      final RenderUnit previousRenderUnit =
          previousRenderTree.getRenderTreeNodeAtIndex(i).getRenderUnit();
      final int newPosition = mRenderTree.getRenderTreeNodeIndex(previousRenderUnit.getId());
      final RenderTreeNode renderTreeNode =
          newPosition > -1 ? mRenderTree.getRenderTreeNodeAtIndex(newPosition) : null;
      final MountItem oldItem = mIdToMountedItemMap.get(previousRenderUnit.getId());

      // if oldItem is null it was previously unmounted so there is nothing we need to do.
      if (oldItem == null) continue;

      final boolean hasUnmountDelegate =
          mUnmountDelegateExtension != null
              && mUnmountDelegateExtension.shouldDelegateUnmount(
                  mMountDelegate.getUnmountDelegateExtensionState(), oldItem);

      if (hasUnmountDelegate) {
        continue;
      }

      if (newPosition == -1) {
        unmountItemRecursively(oldItem.getRenderTreeNode());
      } else {
        final long newHostMarker =
            renderTreeNode.getParent() == null
                ? 0L
                : renderTreeNode.getParent().getRenderUnit().getId();
        final Host newHost =
            mIdToMountedItemMap.get(newHostMarker) == null
                ? null
                : (Host) mIdToMountedItemMap.get(newHostMarker).getContent();

        if (oldItem.getHost() != newHost) {
          // If the id is the same but the parent host is different we simply unmount the item and
          // re-mount it later. If the item to unmount is a ComponentHost, all the children will be
          // recursively unmounted.
          unmountItemRecursively(oldItem.getRenderTreeNode());
        } else if (oldItem.getRenderTreeNode().getPositionInParent()
            != renderTreeNode.getPositionInParent()) {
          // If a MountItem for this id exists and its Host has not changed but its position
          // in the Host has changed we need to update the position in the Host to ensure
          // the z-ordering.
          oldItem
              .getHost()
              .moveItem(
                  oldItem,
                  oldItem.getRenderTreeNode().getPositionInParent(),
                  renderTreeNode.getPositionInParent());
        }
      }
    }

    RenderCoreSystrace.endSection();
  }

  // The content might be null because it's the LayoutSpec for the root host
  // (the very first RenderTreeNode).
  private MountItem mountContentInHost(Object content, Host host, RenderTreeNode node) {
    final MountItem item = new MountItem(node, host, content);

    // Create and keep a MountItem even for the layoutSpec with null content
    // that sets the root host interactions.
    mIdToMountedItemMap.put(node.getRenderUnit().getId(), item);
    host.mount(node.getPositionInParent(), item);

    return item;
  }

  private boolean isMounted(final long id) {
    return mIdToMountedItemMap.get(id) != null;
  }

  private void mountRenderUnit(
      RenderTreeNode renderTreeNode, @Nullable StringBuilder processLogBuilder) {

    if (renderTreeNode.getRenderUnit().getId() == ROOT_HOST_ID) {
      mountRootItem(renderTreeNode);
      return;
    }

    final boolean isTracing = RenderCoreSystrace.isEnabled();
    if (isTracing) {
      RenderCoreSystrace.beginSection("mountRenderTreeNode");
      RenderCoreSystrace.beginSection("MountState.beforeInitialMount");
    }

    // 1. Resolve the correct host to mount our content to.
    final RenderTreeNode hostTreeNode = renderTreeNode.getParent();

    final RenderUnit parentRenderUnit = hostTreeNode.getRenderUnit();
    final RenderUnit renderUnit = renderTreeNode.getRenderUnit();

    // 2. Ensure render tree node's parent is mounted or throw exception depending on the
    // ensure-parent-mounted flag.
    maybeEnsureParentIsMounted(
        renderTreeNode, renderUnit, hostTreeNode, parentRenderUnit, processLogBuilder);

    final MountItem mountItem = mIdToMountedItemMap.get(parentRenderUnit.getId());
    final Object parentContent = mountItem.getContent();
    assertParentContentType(parentContent, renderUnit, parentRenderUnit);

    final Host host = (Host) parentContent;

    // 3. call the RenderUnit's Mount bindings.
    final Object content = MountItemsPool.acquireMountContent(mContext, renderUnit);

    if (mMountDelegate != null) {
      mMountDelegate.startNotifyVisibleBoundsChangedSection();
    }

    if (isTracing) {
      RenderCoreSystrace.endSection();
      RenderCoreSystrace.beginSection("MountState.mountContent");
    }
    mountRenderUnitToContent(mMountDelegate, mContext, renderTreeNode, renderUnit, content);

    // 4. Mount the content into the selected host.
    final MountItem item = mountContentInHost(content, host, renderTreeNode);
    if (isTracing) {
      RenderCoreSystrace.endSection();
      RenderCoreSystrace.beginSection("MountState.initialBind");
    }

    // 5. Call attach binding functions
    bindRenderUnitToContent(mMountDelegate, mContext, item);

    if (isTracing) {
      RenderCoreSystrace.endSection();
      RenderCoreSystrace.beginSection("MountState.applyInitialBounds");
    }

    // 6. Apply the bounds to the Mount content now. It's important to do so after bind as calling
    // bind might have triggered a layout request within a View.
    BoundsUtils.applyBoundsToMountContent(renderTreeNode, item.getContent(), true /* force */);

    if (isTracing) {
      RenderCoreSystrace.endSection();
      RenderCoreSystrace.beginSection("MountState.afterInitialMount");
    }
    if (mMountDelegate != null) {
      mMountDelegate.onBoundsAppliedToItem(renderTreeNode, item.getContent());
      mMountDelegate.endNotifyVisibleBoundsChangedSection();
    }

    if (isTracing) {
      RenderCoreSystrace.endSection();
      RenderCoreSystrace.endSection();
    }
  }

  private void unmountItemRecursively(RenderTreeNode node) {
    final RenderUnit unit = node.getRenderUnit();
    final MountItem item = mIdToMountedItemMap.get(unit.getId());
    // Already has been unmounted.
    if (item == null) {
      return;
    }

    // The root host item cannot be unmounted as it's a reference
    // to the top-level Host, and it is not mounted in a host.
    if (unit.getId() == ROOT_HOST_ID) {
      unmountRootItem();
      return;
    }

    final Object content = item.getContent();
    mIdToMountedItemMap.remove(unit.getId());

    final boolean hasUnmountDelegate =
        mUnmountDelegateExtension != null
            && mUnmountDelegateExtension.shouldDelegateUnmount(
                mMountDelegate.getUnmountDelegateExtensionState(), item);

    // Recursively unmount mounted children items.
    // This is the case when mountDiffing is enabled and unmountOrMoveOldItems() has a matching
    // sub tree. However, traversing the tree bottom-up, it needs to unmount a node holding that
    // sub tree, that will still have mounted items. (Different sequence number on RenderTreeNode
    // id)
    if (node.getChildrenCount() > 0) {

      // unmount all children
      for (int i = 0; i < node.getChildrenCount(); i++) {
        unmountItemRecursively(node.getChildAt(i));
      }

      // check if all items are unmount from the host
      if (!hasUnmountDelegate && ((Host) content).getMountItemCount() > 0) {
        throw new IllegalStateException(
            "Recursively unmounting items from a ComponentHost, left"
                + " some items behind maybe because not tracked by its MountState");
      }
    }

    final Host host = item.getHost();

    if (hasUnmountDelegate) {
      mUnmountDelegateExtension.unmount(
          mMountDelegate.getUnmountDelegateExtensionState(), item, host);
    } else {
      if (item.isBound()) {
        unbindRenderUnitFromContent(mMountDelegate, mContext, item);
      }
      host.unmount(node.getPositionInParent(), item);

      if (content instanceof View) {
        ((View) content).setPadding(0, 0, 0, 0);
      }

      unmountRenderUnitFromContent(mContext, node, unit, content, mMountDelegate);

      item.releaseMountContent(mContext);
    }
  }

  /**
   * Since the root item is not itself mounted on a host, its unmount method is encapsulated into a
   * different method.
   */
  private void unmountRootItem() {
    MountItem item = mIdToMountedItemMap.get(ROOT_HOST_ID);
    if (item != null) {

      if (item.isBound()) {
        unbindRenderUnitFromContent(mMountDelegate, mContext, item);
      }

      mIdToMountedItemMap.remove(ROOT_HOST_ID);

      final RenderTreeNode rootRenderTreeNode = mRenderTree.getRoot();

      unmountRenderUnitFromContent(
          mContext,
          rootRenderTreeNode,
          rootRenderTreeNode.getRenderUnit(),
          item.getContent(),
          mMountDelegate);
    }
  }

  private void mountRootItem(RenderTreeNode rootNode) {
    // Run mount callbacks.
    mountRenderUnitToContent(
        mMountDelegate, mContext, rootNode, rootNode.getRenderUnit(), mRootHost);

    // Create root mount item.
    final MountItem item = new MountItem(rootNode, mRootHost, mRootHost);

    // Adds root mount item to map.
    mIdToMountedItemMap.put(ROOT_HOST_ID, item);

    // Run binder callbacks
    bindRenderUnitToContent(mMountDelegate, mContext, item);
  }

  @Override
  public void unbindMountItem(MountItem mountItem) {
    if (mountItem.isBound()) {
      unbindRenderUnitFromContent(mMountDelegate, mContext, mountItem);
    }
    final Object content = mountItem.getContent();
    if (content instanceof View) {
      ((View) content).setPadding(0, 0, 0, 0);
    }

    unmountRenderUnitFromContent(
        mContext,
        mountItem.getRenderTreeNode(),
        mountItem.getRenderTreeNode().getRenderUnit(),
        content,
        mMountDelegate);

    mountItem.releaseMountContent(mContext);
  }

  private void addExtensions(@Nullable List<Pair<RenderCoreExtension<?, ?>, Object>> extensions) {
    if (extensions != null) {
      if (mMountDelegate == null) {
        mMountDelegate = new MountDelegate(this);
      }
      mMountDelegate.registerExtensions(extensions);
    }
  }

  @Override
  public void unregisterAllExtensions() {
    if (mMountDelegate != null) {
      mMountDelegate.unBind();
      mMountDelegate.unMount();
      mMountDelegate.unregisterAllExtensions();
      mMountDelegate.releaseAllAcquiredReferences();
    }
  }

  private static void mountRenderUnitToContent(
      final @Nullable MountDelegate mountDelegate,
      final Context context,
      final RenderTreeNode node,
      final RenderUnit unit,
      final Object content) {
    unit.mountExtensions(context, content, node.getLayoutData());
    if (mountDelegate != null) {
      mountDelegate.onMountItem(unit, content, node.getLayoutData());
    }
  }

  private static void unmountRenderUnitFromContent(
      final Context context,
      final RenderTreeNode node,
      final RenderUnit unit,
      final Object content,
      final @Nullable MountDelegate mountDelegate) {
    if (mountDelegate != null) {
      mountDelegate.onUnmountItem(unit, content, node.getLayoutData());
    }
    unit.unmountExtensions(context, content, node.getLayoutData());
  }

  private static void bindRenderUnitToContent(
      final @Nullable MountDelegate mountDelegate, Context context, MountItem item) {
    final RenderUnit renderUnit = item.getRenderUnit();
    final Object content = item.getContent();
    final Object layoutData = item.getRenderTreeNode().getLayoutData();
    renderUnit.attachExtensions(context, content, layoutData);
    if (mountDelegate != null) {
      mountDelegate.onBindItem(renderUnit, content, layoutData);
    }
    item.setIsBound(true);
  }

  private static void unbindRenderUnitFromContent(
      final @Nullable MountDelegate mountDelegate, Context context, MountItem item) {
    final RenderUnit renderUnit = item.getRenderUnit();
    final Object content = item.getContent();
    final Object layoutData = item.getRenderTreeNode().getLayoutData();
    if (mountDelegate != null) {
      mountDelegate.onUnbindItem(renderUnit, content, layoutData);
    }
    renderUnit.detachExtensions(context, content, layoutData);
    item.setIsBound(false);
  }

  private static void updateMountItemIfNeeded(
      @Nullable MountDelegate mountDelegate,
      Context context,
      RenderTreeNode renderTreeNode,
      MountItem currentMountItem) {

    RenderCoreSystrace.beginSection("updateMountItemIfNeeded");

    final RenderUnit renderUnit = renderTreeNode.getRenderUnit();
    final Object newLayoutData = renderTreeNode.getLayoutData();
    final RenderTreeNode currentNode = currentMountItem.getRenderTreeNode();
    final RenderUnit currentRenderUnit = currentNode.getRenderUnit();
    final Object currentLayoutData = currentNode.getLayoutData();
    final Object content = currentMountItem.getContent();

    // Re initialize the MountItem internal state with the new attributes from RenderTreeNode
    currentMountItem.update(renderTreeNode);

    currentRenderUnit.onStartUpdateRenderUnit();

    if (currentRenderUnit != renderUnit) {
      RenderCoreSystrace.beginSection("Update Item: ", renderUnit.getDescription());

      renderUnit.updateExtensions(
          context,
          content,
          currentRenderUnit,
          currentLayoutData,
          newLayoutData,
          currentMountItem.isBound());
    }

    currentMountItem.setIsBound(true);

    if (mountDelegate != null) {
      mountDelegate.startNotifyVisibleBoundsChangedSection();
      mountDelegate.onUpdateItemsIfNeeded(
          currentRenderUnit, currentLayoutData, renderUnit, newLayoutData, content);
    }

    // Update the bounds of the mounted content. This needs to be done regardless of whether
    // the RenderUnit has been updated or not since the mounted item might might have the same
    // size and content but a different position.
    updateBoundsForMountedRenderTreeNode(renderTreeNode, currentMountItem, mountDelegate);

    if (mountDelegate != null) {
      mountDelegate.endNotifyVisibleBoundsChangedSection();
    }

    currentRenderUnit.onEndUpdateRenderUnit();

    if (currentRenderUnit != renderUnit) {
      RenderCoreSystrace.endSection(); // UPDATE
    }

    RenderCoreSystrace.endSection();
  }

  private static void assertParentContentType(
      final Object parentContent,
      final RenderUnit<?> renderUnit,
      final RenderUnit<?> parentRenderUnit) {
    if (!(parentContent instanceof Host)) {
      throw new RuntimeException(
          "Trying to mount a RenderTreeNode, its parent should be a Host, but was '"
              + parentContent.getClass().getSimpleName()
              + "'.\n"
              + "Parent RenderUnit: "
              + "id="
              + parentRenderUnit.getId()
              + "; contentType='"
              + parentRenderUnit.getRenderContentType()
              + "'.\n"
              + "Child RenderUnit: "
              + "id="
              + renderUnit.getId()
              + "; contentType='"
              + renderUnit.getRenderContentType()
              + "'.");
    }
  }

  private void maybeEnsureParentIsMounted(
      final RenderTreeNode renderTreeNode,
      final RenderUnit<?> renderUnit,
      final RenderTreeNode hostTreeNode,
      final RenderUnit<?> parentRenderUnit,
      final @Nullable StringBuilder processLogBuilder) {
    if (!isMounted(parentRenderUnit.getId())) {
      if (mEnsureParentMounted) {
        mountRenderUnit(hostTreeNode, processLogBuilder);
      } else {
        final String additionalProcessLog =
            processLogBuilder != null ? processLogBuilder.toString() : "NA";
        throw new HostNotMountedException(
            renderUnit,
            parentRenderUnit,
            "Trying to mount a RenderTreeNode, but its host is not mounted.\n"
                + "Parent RenderUnit: "
                + hostTreeNode.generateDebugString(mRenderTree)
                + "'.\n"
                + "Child RenderUnit: "
                + renderTreeNode.generateDebugString(mRenderTree)
                + "'.\n"
                + "Entire tree:\n"
                + mRenderTree.generateDebugString()
                + ".\n"
                + "Additional Process Log:\n"
                + additionalProcessLog
                + ".\n");
      }
    }
  }
}
