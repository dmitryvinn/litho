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

import static androidx.annotation.Dimension.DP;
import static com.facebook.litho.ComponentContext.NO_SCOPE_EVENT_HANDLER;
import static com.facebook.litho.DynamicPropsManager.KEY_ALPHA;
import static com.facebook.litho.DynamicPropsManager.KEY_BACKGROUND_COLOR;
import static com.facebook.litho.DynamicPropsManager.KEY_BACKGROUND_DRAWABLE;
import static com.facebook.litho.DynamicPropsManager.KEY_ELEVATION;
import static com.facebook.litho.DynamicPropsManager.KEY_FOREGROUND_COLOR;
import static com.facebook.litho.DynamicPropsManager.KEY_ROTATION;
import static com.facebook.litho.DynamicPropsManager.KEY_SCALE_X;
import static com.facebook.litho.DynamicPropsManager.KEY_SCALE_Y;
import static com.facebook.litho.DynamicPropsManager.KEY_TRANSLATION_X;
import static com.facebook.litho.DynamicPropsManager.KEY_TRANSLATION_Y;

import android.animation.AnimatorInflater;
import android.animation.StateListAnimator;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewOutlineProvider;
import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.DimenRes;
import androidx.annotation.Dimension;
import androidx.annotation.DrawableRes;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.Px;
import androidx.annotation.StringRes;
import androidx.annotation.StyleRes;
import androidx.annotation.VisibleForTesting;
import androidx.collection.ArrayMap;
import androidx.core.content.ContextCompat;
import androidx.core.util.Preconditions;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;
import com.facebook.infer.annotation.Nullsafe;
import com.facebook.infer.annotation.ReturnsOwnership;
import com.facebook.infer.annotation.ThreadConfined;
import com.facebook.infer.annotation.ThreadSafe;
import com.facebook.litho.annotations.OnAttached;
import com.facebook.litho.annotations.OnCreateTreeProp;
import com.facebook.litho.annotations.OnDetached;
import com.facebook.litho.drawable.ComparableColorDrawable;
import com.facebook.litho.drawable.ComparableDrawable;
import com.facebook.rendercore.MountItemsPool;
import com.facebook.rendercore.PoolableContentProvider;
import com.facebook.rendercore.transitions.TransitionUtils;
import com.facebook.yoga.YogaAlign;
import com.facebook.yoga.YogaDirection;
import com.facebook.yoga.YogaEdge;
import com.facebook.yoga.YogaJustify;
import com.facebook.yoga.YogaMeasureFunction;
import com.facebook.yoga.YogaPositionType;
import com.facebook.yoga.YogaWrap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a unique instance of a component. To create new {@link Component} instances, use the
 * {@code create()} method in the generated subclass which returns a builder that allows you to set
 * values for individual props. {@link Component} instances are immutable after creation.
 */
@Nullsafe(Nullsafe.Mode.LOCAL)
public abstract class Component
    implements Cloneable,
        PoolableContentProvider,
        HasEventDispatcher,
        HasEventTrigger,
        EventDispatcher,
        EventTriggerTarget,
        Equivalence<Component> {

  // This name needs to match the generated code in specmodels in
  // com.facebook.litho.specmodels.generator.EventCaseGenerator#INTERNAL_ON_ERROR_HANDLER_NAME.
  // Since we cannot easily share this identifier across modules, we verify the consistency through
  // integration tests.
  static final int ERROR_EVENT_HANDLER_ID = "__internalOnErrorHandler".hashCode();
  static final String WRONG_CONTEXT_FOR_EVENT_HANDLER = "Component:WrongContextForEventHandler";
  static final YogaMeasureFunction sMeasureFunction = new LithoYogaMeasureFunction();

  private static final int DEFAULT_MAX_PREALLOCATION = 3;

  @GuardedBy("sTypeIdByComponentType")
  private static final Map<Object, Integer> sTypeIdByComponentType = new HashMap<>();

  private @Nullable ArrayMap<Object, Object> mMetadata = null;

  private static final AtomicInteger sComponentTypeId = new AtomicInteger();
  private static final String MISMATCHING_BASE_CONTEXT = "Component:MismatchingBaseContext";
  private static final String NULL_KEY_SET = "Component:NullKeySet";
  private static final AtomicInteger sIdGenerator = new AtomicInteger(1);
  private static final DynamicValue[] sEmptyArray = new DynamicValue[0];

  /**
   * @return the globally unique ID associated with {@param type}, creating one if necessary.
   *     Allocated IDs map 1-to-1 with objects passed to this method.
   */
  static int getOrCreateId(Object type) {
    synchronized (sTypeIdByComponentType) {
      final Integer typeId = sTypeIdByComponentType.get(type);
      if (typeId != null) {
        return typeId;
      }
      final int nextTypeId = sComponentTypeId.incrementAndGet();
      sTypeIdByComponentType.put(type, nextTypeId);
      return nextTypeId;
    }
  }

  private final int mTypeId;

  private int mId = sIdGenerator.getAndIncrement();
  private @Nullable String mOwnerGlobalKey;
  private @Nullable String mKey;
  private boolean mHasManualKey;
  private @Nullable Handle mHandle;

  // If we have a cachedLayout, onPrepare and onMeasure would have been called on it already.
  private @Nullable CommonProps mCommonProps;
  private @Nullable SparseArray<DynamicValue<?>> mCommonDynamicProps;

  /**
   * Holds an event handler with its dispatcher set to the parent component, or - in case that this
   * is a root component - a default handler that reraises the exception.
   */
  private @Nullable EventHandler<ErrorEvent> mErrorEventHandler;

  @ThreadConfined(ThreadConfined.ANY)
  private @Nullable Context mBuilderContext;

  protected Component() {
    mTypeId = getOrCreateId(getClass());
  }

  /**
   * This constructor should be called only if working with a manually crafted "special" Component.
   * This should NOT be used in general use cases. Use the standard {@link #Component()} instead.
   */
  protected Component(int identityHashCode) {
    mTypeId = getOrCreateId(identityHashCode);
  }

  @Override
  public Object createPoolableContent(Context context) {
    return createMountContent(context);
  }

  @Override
  public Object getPoolableContentType() {
    return getClass();
  }

  @Override
  public boolean isRecyclingDisabled() {
    return poolSize() == 0;
  }

  @Nullable
  @Override
  public MountItemsPool.ItemPool<?> createRecyclingPool() {
    return onCreateMountContentPool();
  }

  @Override
  @Nullable
  public final Object acceptTriggerEvent(
      EventTrigger eventTrigger, Object eventState, Object[] params) {
    try {
      return acceptTriggerEventImpl(eventTrigger, eventState, params);
    } catch (Exception e) {
      if (eventTrigger.mComponentContext != null) {
        ComponentUtils.handle(eventTrigger.mComponentContext, e);
        return null;
      } else {
        throw e;
      }
    }
  }

  protected @Nullable Object acceptTriggerEventImpl(
      EventTrigger eventTrigger, Object eventState, Object[] params) {
    // Do nothing by default
    return null;
  }

  @ThreadSafe(enableChecks = false)
  public final Object createMountContent(Context c) {
    final boolean isTracing = ComponentsSystrace.isTracing();
    if (isTracing) {
      ComponentsSystrace.beginSection("createMountContent:" + ((Component) this).getSimpleName());
    }
    try {
      return onCreateMountContent(c);
    } finally {
      if (isTracing) {
        ComponentsSystrace.endSection();
      }
    }
  }

  @Override
  public final @Nullable Object dispatchOnEvent(EventHandler eventHandler, Object eventState) {
    boolean isTracing = ComponentsSystrace.isTracing();

    // We don't want to wrap and throw error events
    if (eventHandler.id == ERROR_EVENT_HANDLER_ID) {
      if (isTracing) {
        ComponentsSystrace.beginSection("dispatchErrorEvent");
      }
      try {
        return dispatchOnEventImpl(eventHandler, eventState);
      } finally {
        if (isTracing) {
          ComponentsSystrace.endSection();
        }
      }
    }

    final Object token = EventDispatcherInstrumenter.onBeginWork(eventHandler, eventState);
    if (isTracing) {
      ComponentsSystrace.beginSection("dispatchOnEvent");
    }
    try {
      return dispatchOnEventImpl(eventHandler, eventState);
    } catch (Exception e) {
      if (eventHandler.params != null && eventHandler.params[0] instanceof ComponentContext) {
        ComponentUtils.handle((ComponentContext) eventHandler.params[0], e);
        return null;
      } else {
        throw e;
      }
    } finally {
      EventDispatcherInstrumenter.onEndWork(token);
      if (isTracing) {
        ComponentsSystrace.endSection();
      }
    }
  }

  protected @Nullable Object dispatchOnEventImpl(EventHandler eventHandler, Object eventState) {
    if (eventHandler.id == ERROR_EVENT_HANDLER_ID) {
      Preconditions.checkNotNull(
              getErrorHandler(
                  (ComponentContext) Preconditions.checkNotNull(eventHandler.params)[0]))
          .dispatchEvent((ErrorEvent) eventState);
    }

    // Don't do anything by default, unless we're handling an error.
    return null;
  }

  /**
   * This indicates the type of the {@link Object} that will be returned by {@link
   * com.facebook.litho.Component#mount}.
   *
   * @return one of {@link com.facebook.litho.Component.MountType}
   */
  public com.facebook.litho.Component.MountType getMountType() {
    return com.facebook.litho.Component.MountType.NONE;
  }

  final void bind(
      final ComponentContext c,
      final Object mountedContent,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    if (c != null) {
      c.enterNoStateUpdatesMethod("bind");
    }
    final boolean isTracing = ComponentsSystrace.isTracing();
    if (isTracing) {
      ComponentsSystrace.beginSection("onBind:" + ((Component) this).getSimpleName());
    }
    try {
      onBind(c, mountedContent, interStagePropsContainer);
    } catch (Exception e) {
      if (c != null) {
        ComponentUtils.handle(c, e);
      } else {
        throw e;
      }
    } finally {
      if (c != null) {
        c.exitNoStateUpdatesMethod();
      }
      if (isTracing) {
        ComponentsSystrace.endSection();
      }
    }
  }

  final @Nullable Transition createTransition(ComponentContext c) {
    final Transition transition = onCreateTransition(c);
    if (transition != null) {
      TransitionUtils.setOwnerKey(transition, c.getGlobalKey());
    }
    return transition;
  }

  public final int getTypeId() {
    return mTypeId;
  }

  final void loadStyle(ComponentContext c, @AttrRes int defStyleAttr, @StyleRes int defStyleRes) {
    c.setDefStyle(defStyleAttr, defStyleRes);
    onLoadStyle(c);
    c.setDefStyle(0, 0);
  }

  final void loadStyle(ComponentContext c) {
    onLoadStyle(c);
  }

  final void mount(
      final ComponentContext c,
      final Object convertContent,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    if (c != null) {
      c.enterNoStateUpdatesMethod("mount");
    }
    final boolean isTracing = ComponentsSystrace.isTracing();
    if (isTracing) {
      ComponentsSystrace.beginSection("onMount:" + ((Component) this).getSimpleName());
    }
    try {
      onMount(c, convertContent, interStagePropsContainer);
    } catch (Exception e) {
      if (c != null) {
        ComponentUtils.handle(c, e);
      } else {
        throw e;
      }
    } finally {
      if (c != null) {
        c.exitNoStateUpdatesMethod();
      }
      if (isTracing) {
        ComponentsSystrace.endSection();
      }
    }
  }

  final void unbind(
      final ComponentContext c,
      final Object mountedContent,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    try {
      onUnbind(c, mountedContent, interStagePropsContainer);
    } catch (Exception e) {
      ComponentUtils.handle(c, e);
    }
  }

  final void unmount(
      final ComponentContext c,
      final Object mountedContent,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    try {
      onUnmount(c, mountedContent, interStagePropsContainer);
    } catch (Exception e) {
      ComponentUtils.handle(c, e);
    }
  }

  protected void applyPreviousRenderData(
      com.facebook.litho.Component.RenderData previousRenderData) {}

  /**
   * Whether this {@link com.facebook.litho.Component} is able to measure itself according to
   * specific size constraints.
   */
  protected boolean canMeasure() {
    return false;
  }

  /** @return true if this component can be preallocated. */
  protected boolean canPreallocate() {
    return false;
  }

  protected void createInitialState(ComponentContext c) {}

  protected void dispatchOnEnteredRange(ComponentContext c, String name) {
    // Do nothing by default
  }

  protected void dispatchOnExitedRange(ComponentContext c, String name) {
    // Do nothing by default
  }

  /**
   * Get extra accessibility node id at a given point within the component.
   *
   * @param x x co-ordinate within the mounted component
   * @param y y co-ordinate within the mounted component
   * @return the extra virtual view id if one is found, otherwise {@code
   *     ExploreByTouchHelper#INVALID_ID}
   */
  protected int getExtraAccessibilityNodeAt(
      final ComponentContext c,
      final int x,
      final int y,
      final @Nullable InterStagePropsContainer InterStagePropsContainer) {
    return ExploreByTouchHelper.INVALID_ID;
  }

  /**
   * The number of extra accessibility nodes that this component wishes to provides to the
   * accessibility system.
   *
   * @return the number of extra nodes
   */
  protected int getExtraAccessibilityNodesCount(
      final ComponentContext c, final @Nullable InterStagePropsContainer interStagePropsContainer) {
    return 0;
  }

  /** Updates the TreeProps map with outputs from all {@link OnCreateTreeProp} methods. */
  protected @Nullable TreeProps getTreePropsForChildren(
      ComponentContext c, @Nullable TreeProps treeProps) {
    return treeProps;
  }

  /**
   * @return true if the component implements {@link OnAttached} or {@link OnDetached} delegate
   *     methods.
   */
  protected boolean hasAttachDetachCallback() {
    return false;
  }

  /**
   * Whether this {@link com.facebook.litho.Component} mounts views that contain component-based
   * content that can be incrementally mounted e.g. if the mounted view has a LithoView with
   * incremental mount enabled.
   */
  protected boolean hasChildLithoViews() {
    return false;
  }

  /** @return true if the Component is using state, false otherwise. */
  protected boolean hasState() {
    return false;
  }

  protected boolean usesLocalStateContainer() {
    return false;
  }

  /**
   * Whether this component will populate any accessibility nodes or events that are passed to it.
   *
   * @return true if the component implements accessibility info
   */
  protected boolean implementsAccessibility() {
    return false;
  }

  /**
   * Whether this component will expose any virtual views to the accessibility framework
   *
   * @return true if the component exposes extra accessibility nodes
   */
  protected boolean implementsExtraAccessibilityNodes() {
    return false;
  }

  protected boolean implementsShouldUpdate() {
    return false;
  }

  /** @return true if Mount uses @FromMeasure or @FromOnBoundsDefined parameters. */
  protected boolean isMountSizeDependent() {
    return false;
  }

  protected boolean isPureRender() {
    return false;
  }

  protected boolean needsPreviousRenderData() {
    return false;
  }

  /**
   * Called when the component is attached to the {@link ComponentTree}.
   *
   * @param c The {@link ComponentContext} the Component was constructed with.
   */
  protected void onAttached(ComponentContext c) {}

  protected void onBind(
      final ComponentContext c,
      final Object mountedContent,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    // Do nothing by default.
  }

  /**
   * Called after the layout calculation is finished and the given {@link ComponentLayout} has its
   * bounds defined. You can use {@link ComponentLayout#getX()}, {@link ComponentLayout#getY()},
   * {@link ComponentLayout#getWidth()}, and {@link ComponentLayout#getHeight()} to get the size and
   * position of the component in the layout tree.
   *
   * @param c The {@link Context} used by this component.
   * @param layout The {@link ComponentLayout} with defined position and size.
   */
  protected void onBoundsDefined(
      final ComponentContext c,
      final ComponentLayout layout,
      @Nullable InterStagePropsContainer interStagePropsContainer) {}

  /**
   * Invokes the Component-specific render implementation, returning a RenderResult. The
   * RenderResult will have the Component this Component rendered to (which will then need to be
   * render()'ed or {@link #resolve(LayoutStateContext, ComponentContext)}'ed), as well as other
   * metadata from that render call such as transitions that should be applied.
   */
  protected RenderResult render(ComponentContext c, int widthSpec, int heightSpec) {
    throw new RuntimeException(
        "Render should not be called on a component which hasn't implemented render! "
            + getSimpleName());
  }

  /**
   * Create the object that will be mounted in the {@link LithoView}.
   *
   * @param context The {@link Context} to be used to create the content.
   * @return an Object that can be mounted for this component.
   */
  protected Object onCreateMountContent(Context context) {
    throw new RuntimeException(
        "Trying to mount a MountSpec that doesn't implement @OnCreateMountContent");
  }

  /**
   * @return the MountContentPool that should be used to recycle mount content for this mount spec.
   */
  protected MountContentPool onCreateMountContentPool() {
    return new DefaultMountContentPool(getClass().getSimpleName(), poolSize(), true);
  }

  /**
   * @return a {@link TransitionSet} specifying how to animate this component to its new layout and
   *     props.
   */
  protected @Nullable Transition onCreateTransition(ComponentContext c) {
    return null;
  }

  /**
   * Called when the component is detached from the {@link ComponentTree}.
   *
   * @param c The {@link ComponentContext} the Component was constructed with.
   */
  protected void onDetached(ComponentContext c) {}

  /**
   * Called to provide a fallback if a supported lifecycle method throws an exception. It is
   * possible to either recover from the error here or reraise the exception to catch it at a higher
   * level or crash the application.
   *
   * @see com.facebook.litho.annotations.OnError
   * @param c The {@link ComponentContext} the Component was constructed with.
   * @param e The exception caught.
   */
  protected void onError(ComponentContext c, Exception e) {
    EventHandler<ErrorEvent> eventHandler = c.getErrorEventHandler();
    if (eventHandler == null) {
      if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      } else {
        throw new RuntimeException(e);
      }
    }
    ErrorEvent errorEvent = new ErrorEvent();
    errorEvent.exception = e;
    errorEvent.componentTree = c != null ? c.getComponentTree() : null;
    eventHandler.dispatchEvent(errorEvent);
  }

  protected void onLoadStyle(ComponentContext c) {}

  protected void onMeasure(
      final ComponentContext c,
      final ComponentLayout layout,
      final int widthSpec,
      final int heightSpec,
      final Size size,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    throw new IllegalStateException(
        "You must override onMeasure() if you return true in canMeasure(), "
            + "Component is: "
            + this);
  }

  /**
   * Called during layout calculation to determine the baseline of a component.
   *
   * @param c The {@link Context} used by this component.
   * @param width The width of this component.
   * @param height The height of this component.
   * @param interStagePropsContainer
   */
  protected int onMeasureBaseline(
      final ComponentContext c,
      final int width,
      final int height,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    return height;
  }

  /**
   * Deploy all UI elements representing the final bounds defined in the given {@link
   * ComponentLayout}. Return either a {@link Drawable} or a {@link View} or {@code null} to be
   * mounted.
   *
   * @param c The {@link ComponentContext} to mount the component into.
   */
  protected void onMount(
      final ComponentContext c,
      final Object convertContent,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    // Do nothing by default.
  }

  /**
   * Populate an accessibility node with information about the component.
   *
   * @param accessibilityNode node to populate
   */
  protected void onPopulateAccessibilityNode(
      final ComponentContext c,
      final View host,
      final AccessibilityNodeInfoCompat accessibilityNode,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {}

  /**
   * Populate an extra accessibility node.
   *
   * @param accessibilityNode node to populate
   * @param extraNodeIndex index of extra node
   * @param componentBoundsX left bound of the mounted component
   * @param componentBoundsY top bound of the mounted component
   * @param interStagePropsContainer
   */
  protected void onPopulateExtraAccessibilityNode(
      final ComponentContext c,
      final AccessibilityNodeInfoCompat accessibilityNode,
      final int extraNodeIndex,
      final int componentBoundsX,
      final int componentBoundsY,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {}

  protected @Nullable PrepareResult prepare(ComponentContext c) {
    // default implementation runs onPrepare(), MountableComponents will override to return a
    // Mountable
    onPrepare(c);
    return null;
  }

  protected void onPrepare(ComponentContext c) {
    // do nothing, by default
  }

  protected void onUnbind(
      final ComponentContext c,
      final Object mountedContent,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    // Do nothing by default.
  }

  /**
   * Unload UI elements associated with this component.
   *
   * @param c The {@link Context} for this mount operation.
   * @param mountedContent The {@link Drawable} or {@link View} mounted by this component.
   * @param interStagePropsContainer
   */
  protected void onUnmount(
      final ComponentContext c,
      final Object mountedContent,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    // Do nothing by default.
  }

  @ThreadSafe
  protected int poolSize() {
    return DEFAULT_MAX_PREALLOCATION;
  }

  /**
   * Retrieves all of the tree props used by this Component from the TreeProps map and sets the tree
   * props as fields on the ComponentImpl.
   */
  protected void populateTreeProps(@Nullable TreeProps parentTreeProps) {}

  protected @Nullable com.facebook.litho.Component.RenderData recordRenderData(
      ComponentContext c, com.facebook.litho.Component.RenderData toRecycle) {
    return null;
  }

  /** Resolves the {@link ComponentLayout} for the given {@link Component}. */
  protected @Nullable LithoNode resolve(
      final LayoutStateContext layoutContext, final ComponentContext c) {
    return Layout.create(layoutContext, c, this);
  }

  /**
   * @return true if the Component should always be measured when receiving a remeasure event, false
   *     otherwise.
   */
  protected boolean shouldAlwaysRemeasure() {
    return false;
  }

  protected boolean isEqualivalentTreeProps(ComponentContext current, ComponentContext next) {
    return true;
  }

  final boolean shouldComponentUpdate(
      final @Nullable ComponentContext previousScopedContext,
      @Nullable Component currentComponent,
      final @Nullable ComponentContext nextScopedContext,
      @Nullable Component nextComponent) {
    final boolean shouldUpdate =
        shouldUpdate(
            currentComponent,
            currentComponent == null || previousScopedContext == null
                ? null
                : currentComponent.getStateContainer(previousScopedContext),
            nextComponent,
            nextComponent == null || nextScopedContext == null
                ? null
                : nextComponent.getStateContainer(nextScopedContext));

    if (!implementsShouldUpdate()) {
      return shouldUpdate
          || (previousScopedContext != null
              && nextScopedContext != null
              && currentComponent != null
              && !currentComponent.isEqualivalentTreeProps(
                  previousScopedContext, nextScopedContext));
    }

    return shouldUpdate;
  }

  /**
   * Whether the component needs updating.
   *
   * <p>For layout components, the framework will verify that none of the children of the component
   * need updating, and that both components have the same number of children. Therefore this method
   * just needs to determine any changes to the top-level component that would cause it to need to
   * be updated (for example, a click handler was added).
   *
   * <p>For mount specs, the framework does nothing extra and this method alone determines whether
   * the component is updated or not.
   *
   * @param previous the previous component to compare against.
   * @param next the component that is now in use.
   * @return true if the component needs an update, false otherwise.
   */
  protected boolean shouldUpdate(
      final @Nullable Component previous,
      final @Nullable StateContainer prevStateContainer,
      final @Nullable Component next,
      final @Nullable StateContainer nextStateContainer) {
    if (!isPureRender()) {
      return true;
    }

    return previous == null
        || !previous.isEquivalentTo(next)
        || !ComponentUtils.hasEquivalentState(prevStateContainer, nextStateContainer);
  }

  /**
   * Call this to transfer the {@link com.facebook.litho.annotations.State} annotated values between
   * two {@link Component} with the same global scope.
   */
  protected void transferState(
      StateContainer previousStateContainer, StateContainer nextStateContainer) {}

  /** For internal use, only. */
  public static void dispatchErrorEvent(ComponentContext c, ErrorEvent e) {
    ComponentUtils.dispatchErrorEvent(c, e);
  }

  @Nullable
  protected static EventTrigger getEventTrigger(ComponentContext c, int id, String key) {
    if (c.getComponentScope() == null) {
      return null;
    }

    return c.getComponentTree().getEventTrigger(c.getGlobalKey() + id + key);
  }

  @Nullable
  protected static EventTrigger getEventTrigger(ComponentContext c, int id, Handle handle) {
    if (handle.getComponentTree() == null) {
      return null;
    }

    return handle.getComponentTree().getEventTrigger(handle, id);
  }

  /**
   * This method is overridden in the generated component to return true if and only if the
   * Component Spec has an OnError lifecycle callback.
   */
  protected boolean hasOwnErrorHandler() {
    return false;
  }

  protected static <E> EventHandler<E> newEventHandler(
      final Class<? extends Component> reference,
      final String className,
      final ComponentContext c,
      final int id,
      final Object[] params) {
    if (c == null || c.getComponentScope() == null) {
      ComponentsReporter.emitMessage(
          ComponentsReporter.LogLevel.FATAL,
          NO_SCOPE_EVENT_HANDLER,
          "Creating event handler without scope.");
      return NoOpEventHandler.getNoOpEventHandler();
    } else if (reference != c.getComponentScope().getClass()) {
      ComponentsReporter.emitMessage(
          ComponentsReporter.LogLevel.ERROR,
          WRONG_CONTEXT_FOR_EVENT_HANDLER + ":" + c.getComponentScope().getSimpleName(),
          String.format(
              "A Event handler from %s was created using a context from %s. "
                  + "Event Handlers must be created using a ComponentContext from its Component.",
              className, c.getComponentScope().getSimpleName()));
    }
    final EventHandler<E> eventHandler = c.newEventHandler(id, params);
    if (c.getComponentTree() != null) {
      c.getComponentTree().recordEventHandler(c, eventHandler);
    }

    return eventHandler;
  }

  /**
   * This variant is used to create an EventTrigger used to register this component as a target in
   * {@link EventTriggersContainer}
   */
  protected static <E> EventTrigger<E> newEventTrigger(
      ComponentContext c, Component component, int methodId) {
    return c.newEventTrigger(methodId, component.getKey(), component.getHandle());
  }

  /**
   * This is used to create a Trigger to be invoked later, e.g. in the context of the deprecated
   * trigger API TextInput.requestFocusTrigger(c, "my_key").
   */
  @Deprecated
  protected static <E> EventTrigger<E> newEventTrigger(
      ComponentContext c, String childKey, int methodId) {
    return c.newEventTrigger(methodId, childKey, null);
  }

  public enum MountType {
    NONE,
    DRAWABLE,
    VIEW,
    MOUNTABLE /* For internal use only. Used only by Kotlin MountableComponent */
  }

  /**
   * Generated component's state container could implement this interface along with {@link
   * StateContainer} when componentspec specifies state update method with {@link
   * com.facebook.litho.annotations.OnUpdateStateWithTransition} annotation.
   */
  public interface TransitionContainer {

    /** Remove and return transition provided from OnUpdateStateWithTransition. */
    Transition consumeTransition();
  }

  /**
   * A per-Component-class data structure to keep track of some of the last mounted @Prop/@State
   * params a component was rendered with. The exact params that are tracked are just the ones
   * needed to support that Component's use of {@link Diff} params in their lifecycle methods that
   * allow Diff params (e.g. {@link #onCreateTransition}).
   */
  public interface RenderData {}

  @Nullable
  public final CommonProps getCommonProps() {
    return mCommonProps;
  }

  @Deprecated
  @Override
  public final EventDispatcher getEventDispatcher() {
    return this;
  }

  public String getSimpleName() {
    return getClass().getSimpleName();
  }

  public final boolean hasClickHandlerSet() {
    return mCommonProps != null
        && mCommonProps.getNullableNodeInfo() != null
        && mCommonProps.getNullableNodeInfo().getClickHandler() != null;
  }

  /**
   * Compares this component to a different one to check if they are the same
   *
   * <p>This is used to be able to skip rendering a component again. We avoid using the {@link
   * Object#equals(Object)} so we can optimize the code better over time since we don't have to
   * adhere to the contract required for a equals method.
   *
   * @param other the component to compare to
   * @return true if the components are of the same type and have the same props
   */
  @Override
  public boolean isEquivalentTo(@Nullable Component other) {
    if (this == other) {
      return true;
    }
    if (other == null || getClass() != other.getClass()) {
      return false;
    }
    if (getId() == other.getId()) {
      return true;
    }

    return ComponentUtils.hasEquivalentFields(this, other);
  }

  public Component makeShallowCopy() {
    try {
      return (Component) super.clone();
    } catch (CloneNotSupportedException e) {
      // This class implements Cloneable, so this is impossible
      throw new RuntimeException(e);
    }
  }

  /**
   * Measure a component with the given {@link SizeSpec} constrain.
   *
   * @param c {@link ComponentContext}.
   * @param widthSpec Width {@link SizeSpec} constrain.
   * @param heightSpec Height {@link SizeSpec} constrain.
   * @param outputSize Size object that will be set with the measured dimensions.
   */
  public final void measure(ComponentContext c, int widthSpec, int heightSpec, Size outputSize) {
    measure(c, widthSpec, heightSpec, outputSize, true);
  }

  public final void measure(
      final ComponentContext c,
      final int widthSpec,
      final int heightSpec,
      final Size outputSize,
      final boolean shouldCacheResult) {

    final LayoutState layoutState;

    if (shouldCacheResult && c.getLayoutState() != null) {
      layoutState = c.getLayoutState();
    } else if (!shouldCacheResult) {
      layoutState = new LayoutState(c, this, new StateHandler(), null, null, null);
    } else {
      throw new IllegalStateException(
          getSimpleName()
              + ": Trying to measure a component outside of a LayoutState calculation. "
              + "If that is what you must do, see Component#measureMightNotCacheInternalNode.");
    }

    LithoLayoutResult lastMeasuredLayout = layoutState.getCachedLayout(this);
    if (lastMeasuredLayout == null
        || !MeasureComparisonUtils.isMeasureSpecCompatible(
            lastMeasuredLayout.getLastWidthSpec(), widthSpec, lastMeasuredLayout.getWidth())
        || !MeasureComparisonUtils.isMeasureSpecCompatible(
            lastMeasuredLayout.getLastHeightSpec(), heightSpec, lastMeasuredLayout.getHeight())) {
      layoutState.clearCachedLayout(this);

      final LayoutResultHolder container =
          Layout.createAndMeasureComponent(
              Preconditions.checkNotNull(layoutState.getLayoutStateContext()),
              c,
              this,
              widthSpec,
              heightSpec);
      if (container.wasLayoutInterrupted()) {
        return;
      }

      lastMeasuredLayout = container.mResult;

      if (lastMeasuredLayout == null) {
        return;
      }

      layoutState.addLastMeasuredLayout(this, lastMeasuredLayout);

      // This component resolution won't be deferred nor onMeasure called if it's a layout spec.
      // In that case it needs to manually save the latest saze specs.
      // The size specs will be checked during the calculation (or collection) of the main tree.
      if (Component.isLayoutSpec(this)) {
        lastMeasuredLayout.setLastWidthSpec(widthSpec);
        lastMeasuredLayout.setLastHeightSpec(heightSpec);
        lastMeasuredLayout.setLastMeasuredWidth(lastMeasuredLayout.getWidth());
        lastMeasuredLayout.setLastMeasuredHeight(lastMeasuredLayout.getHeight());
      }
    }
    outputSize.width = lastMeasuredLayout.getWidth();
    outputSize.height = lastMeasuredLayout.getHeight();

    if (!shouldCacheResult) {
      layoutState.clearCachedLayout(this);
    }
  }

  /**
   * Should not be used! Components should be manually measured only as part of a LayoutState
   * calculation. This will measure a component and set the size in the outputSize object but the
   * measurement result will not be cached and reused for future measurements of this component.
   *
   * <p>This is very inefficient because it throws away the InternalNode from measuring here and
   * will have to remeasure when the component needs to be measured as part of a LayoutState. This
   * will lead to suboptimal performance.
   *
   * <p>You probably don't need to use this. If you really need to measure your Component outside of
   * a LayoutState calculation reach out to the Litho team to discuss an alternative solution.
   *
   * <p>If this is called during a LayoutState calculation, it will delegate to {@link
   * Component#onMeasure(ComponentContext, ComponentLayout, int, int, Size,
   * InterStagePropsContainer)}, which does cache the measurement result for the duration of this
   * LayoutState.
   */
  @Deprecated
  public final void measureMightNotCacheInternalNode(
      ComponentContext c, int widthSpec, int heightSpec, Size outputSize) {

    if (c.getLayoutState() != null) {
      measure(c, widthSpec, heightSpec, outputSize);
      return;
    }

    // At this point we're trying to measure the Component outside of a LayoutState calculation.
    // The state values are irrelevant in this scenario - outside of a LayoutState they should be
    // the default/initial values. The LayoutStateContext is not expected to contain any info.
    final LayoutState layoutState = new LayoutState(c, this, new StateHandler(), null, null, null);
    final LayoutStateContext layoutStateContext = layoutState.getLayoutStateContext();
    final ComponentContext contextForLayout =
        new ComponentContext(c, c.getTreeProps(), layoutStateContext);

    final LayoutResultHolder holder =
        Layout.createAndMeasureComponent(
            layoutStateContext, contextForLayout, this, widthSpec, heightSpec);

    if (holder.wasLayoutInterrupted()) {
      outputSize.height = 0;
      outputSize.width = 0;
    } else {
      final @Nullable LithoLayoutResult result = holder.mResult;
      outputSize.height = result != null ? result.getHeight() : 0;
      outputSize.width = result != null ? result.getWidth() : 0;
    }
  }

  @Override
  public void recordEventTrigger(ComponentContext c, EventTriggersContainer container) {
    // Do nothing by default
  }

  @Nullable
  final LithoNode consumeLayoutCreatedInWillRender(
      final @Nullable LayoutStateContext layoutStateContext, @Nullable ComponentContext context) {
    LithoNode layout;

    if (context == null || layoutStateContext == null) {
      return null;
    }

    layout = layoutStateContext.consumeLayoutCreatedInWillRender(mId);

    if (layout != null) {
      assertSameBaseContext(context, layout.getAndroidContext());
    }

    if (layout == null) {
      return null;
    }

    return layout;
  }

  @VisibleForTesting
  @Nullable
  final LithoNode getLayoutCreatedInWillRender(final LayoutStateContext layoutStateContext) {
    return layoutStateContext.getLayoutCreatedInWillRender(mId);
  }

  private void setLayoutCreatedInWillRender(
      final LayoutStateContext layoutStateContext, final @Nullable LithoNode newValue) {
    layoutStateContext.setLayoutCreatedInWillRender(mId, newValue);
  }

  /**
   * @return {@link SparseArray} that holds common dynamic Props
   * @see DynamicPropsManager
   */
  @Nullable
  SparseArray<DynamicValue<?>> getCommonDynamicProps() {
    return mCommonDynamicProps;
  }

  /**
   * @return The error handler dispatching to either the parent component if available, or reraising
   *     the exception. Null if the component isn't initialized.
   */
  @Nullable
  final EventHandler<ErrorEvent> getErrorHandler(ComponentContext scopedContext) {
    return scopedContext.getScopedComponentInfo().getErrorEventHandler();
  }

  protected final @Nullable EventHandler<ErrorEvent> getErrorHandler() {
    return mErrorEventHandler;
  }

  /** This setter should only be called during the render phase of the component, never after. */
  final void setErrorEventHandlerDuringRender(EventHandler<ErrorEvent> errorHandler) {
    mErrorEventHandler = errorHandler;
  }

  /** @return a handle that is unique to this component. */
  @Nullable
  public final Handle getHandle() {
    return mHandle;
  }

  /**
   * Set a handle that is unique to this component.
   *
   * @param handle handle
   */
  final void setHandle(@Nullable Handle handle) {
    mHandle = handle;
  }

  /** @return a key that is local to the component's parent. */
  final String getKey() {
    if (mKey == null) {
      if (mHasManualKey) {
        throw new IllegalStateException(
            "Should not have null manual key! (" + getSimpleName() + ")");
      }
      mKey = Integer.toString(getTypeId());
    }
    return mKey;
  }

  /**
   * Set a key that is local to the parent of this component.
   *
   * @param key key
   */
  final void setKey(String key) {
    mHasManualKey = true;
    if (key == null) {
      throw new IllegalArgumentException("key must not be null");
    }
    mKey = key;
  }

  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  @Nullable
  final String getOwnerGlobalKey() {
    return mOwnerGlobalKey;
  }

  /**
   * @return true if component has common dynamic props, false - otherwise. If so {@link
   *     #getCommonDynamicProps()} will return not null value
   * @see DynamicPropsManager
   */
  boolean hasCommonDynamicProps() {
    return mCommonDynamicProps != null && mCommonDynamicProps.size() > 0;
  }

  /** @return if has a handle set */
  final boolean hasHandle() {
    return mHandle != null;
  }

  /** @return if has a manually set key */
  final boolean hasManualKey() {
    return mHasManualKey;
  }

  final Component makeShallowCopyWithNewId() {
    final Component component = makeShallowCopy();
    component.mId = sIdGenerator.incrementAndGet();
    return component;
  }

  static void markLayoutStarted(Component component, LayoutStateContext layoutStateContext) {
    layoutStateContext.markLayoutStarted();
  }

  protected void bindDynamicProp(int dynamicPropIndex, @Nullable Object value, Object content) {
    throw new RuntimeException("Components that have dynamic Props must override this method");
  }

  /**
   * Indicate that this component implements its own {@link #resolve(LayoutStateContext,
   * ComponentContext)} logic instead of going through {@link #render(ComponentContext)}.
   */
  boolean canResolve() {
    return false;
  }

  // This will not be needed anymore for stateless components.
  protected void copyInterStageImpl(
      final @Nullable InterStagePropsContainer copyIntoInterStagePropsContainer,
      final @Nullable InterStagePropsContainer copyFromInterStagePropsContainer) {}

  // This will not be needed anymore for stateless components.
  protected void copyPrepareInterStageImpl(
      final @Nullable PrepareInterStagePropsContainer copyIntoInterStagePropsContainer,
      final @Nullable PrepareInterStagePropsContainer copyFromInterStagePropsContainer) {}

  protected DynamicValue[] getDynamicProps() {
    return sEmptyArray;
  }

  // Get an id that is identical across cloned instances, but otherwise unique
  final int getId() {
    return mId;
  }

  protected static @Nullable StateContainer getStateContainer(
      final ComponentContext scopedContext, Component component) {
    return scopedContext.getScopedComponentInfo().getStateContainer();
  }

  final @Nullable StateContainer getStateContainer(final @Nullable ComponentContext scopedContext) {
    if (scopedContext == null) {
      throw new IllegalStateException(
          "Cannot access a state container outside of a layout state calculation.");
    }

    if (!hasState()) {
      return null;
    }

    return scopedContext.getScopedComponentInfo().getStateContainer();
  }

  protected @Nullable StateContainer createStateContainer() {
    return null;
  }

  @Override
  public final String toString() {
    return getSimpleName();
  }

  protected final @Nullable InterStagePropsContainer getInterStagePropsContainer(
      final ComponentContext scopedContext,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    return interStagePropsContainer;
  }

  protected final @Nullable PrepareInterStagePropsContainer getPrepareInterStagePropsContainer(
      final ComponentContext scopedContext) {
    return scopedContext.getScopedComponentInfo().getPrepareInterStagePropsContainer();
  }

  protected @Nullable InterStagePropsContainer createInterStagePropsContainer() {
    return null;
  }

  protected @Nullable PrepareInterStagePropsContainer createPrepareInterStagePropsContainer() {
    return null;
  }

  /**
   * @return {@link SparseArray} that holds common dynamic Props, initializing it beforehand if
   *     needed
   * @see DynamicPropsManager
   */
  final SparseArray<DynamicValue<?>> getOrCreateCommonDynamicProps() {
    if (mCommonDynamicProps == null) {
      mCommonDynamicProps = new SparseArray<>();
    }
    return mCommonDynamicProps;
  }

  final CommonProps getOrCreateCommonProps() {
    if (mCommonProps == null) {
      mCommonProps = new CommonProps();
    }

    return mCommonProps;
  }

  private boolean hasCachedLayout(final LayoutStateContext layoutStateContext) {
    final LayoutState layoutState = layoutStateContext.getLayoutState();

    if (layoutState != null) {
      return layoutState.hasCachedLayout(this);
    }

    return false;
  }

  /**
   * @return whether the given component will render because it returns non-null from its resolved
   *     onCreateLayout, based on its current props and state. Returns true if the resolved layout
   *     is non-null, otherwise false.
   * @deprecated Using willRender is regarded as an anti-pattern, since it will load all classes
   *     into memory in order to potentially decide not to use any of them.
   */
  @Deprecated
  public static boolean willRender(ComponentContext c, Component component) {
    if (component == null) {
      return false;
    }

    final LayoutStateContext layoutStateContext =
        Preconditions.checkNotNull(c.getLayoutStateContext());

    final LithoNode componentLayoutCreatedInWillRender =
        component.getLayoutCreatedInWillRender(layoutStateContext);
    if (componentLayoutCreatedInWillRender != null) {
      return willRender(layoutStateContext, c, component, componentLayoutCreatedInWillRender);
    }

    final LithoNode newLayoutCreatedInWillRender = Layout.create(layoutStateContext, c, component);
    boolean willRender = willRender(layoutStateContext, c, component, newLayoutCreatedInWillRender);
    if (willRender) { // do not cache NoOpInternalNode(NULL_LAYOUT)
      component.setLayoutCreatedInWillRender(layoutStateContext, newLayoutCreatedInWillRender);
    }
    return willRender;
  }

  static boolean isHostSpec(@Nullable Component component) {
    return (component instanceof HostComponent);
  }

  static boolean isLayoutSpec(@Nullable Component component) {
    return (component != null && component.getMountType() == MountType.NONE);
  }

  static boolean isLayoutSpecWithSizeSpec(@Nullable Component component) {
    return component != null
        && component.getMountType() == MountType.NONE
        && component.canMeasure();
  }

  static boolean isMountSpec(@Nullable Component component) {
    return (component != null && component.getMountType() != MountType.NONE);
  }

  static boolean isMountable(@Nullable Component component) {
    return (component != null && component.getMountType() == MountType.MOUNTABLE);
  }

  static boolean isNestedTree(@Nullable Component component) {
    return isLayoutSpecWithSizeSpec(component);
  }

  static boolean hasCachedLayout(final LayoutStateContext context, final Component component) {
    return component.hasCachedLayout(context);
  }

  /** @return whether the given component is a pure render component. */
  @VisibleForTesting
  public static boolean isPureRender(@Nullable Component component) {
    return component != null && component.isPureRender();
  }

  /** Store a working range information into a list for later use by {@link LayoutState}. */
  protected static void registerWorkingRange(
      ComponentContext scopedContext,
      String name,
      WorkingRange workingRange,
      Component component,
      String globalKey) {
    scopedContext
        .getScopedComponentInfo()
        .registerWorkingRange(name, workingRange, component, globalKey);
  }

  protected static @Nullable <T> T retrieveValue(@Nullable DynamicValue<T> dynamicValue) {
    return dynamicValue != null ? dynamicValue.get() : null;
  }

  private static void assertSameBaseContext(
      ComponentContext scopedContext, Context willRenderContext) {
    if (scopedContext.getAndroidContext() != willRenderContext) {
      ComponentsReporter.emitMessage(
          ComponentsReporter.LogLevel.ERROR,
          MISMATCHING_BASE_CONTEXT,
          "Found mismatching base contexts between the Component's Context ("
              + scopedContext.getAndroidContext()
              + ") and the Context used in willRender ("
              + willRenderContext
              + ")!");
    }
  }

  private static boolean willRender(
      final LayoutStateContext layoutStateContext,
      ComponentContext context,
      Component component,
      @Nullable LithoNode node) {
    if (node == null) {
      return false;
    }

    if (node instanceof NestedTreeHolder) {
      // Components using @OnCreateLayoutWithSizeSpec are lazily resolved after the rest of the tree
      // has been measured (so that we have the proper measurements to pass in). This means we can't
      // eagerly check the result of OnCreateLayoutWithSizeSpec.
      component.consumeLayoutCreatedInWillRender(
          layoutStateContext, context); // Clear the layout created in will render
      throw new IllegalArgumentException(
          "Cannot check willRender on a component that uses @OnCreateLayoutWithSizeSpec! "
              + "Try wrapping this component in one that uses @OnCreateLayout if possible.");
    }

    return true;
  }

  @Override
  public final int hashCode() {
    return super.hashCode();
  }

  @Override
  public final boolean equals(@Nullable Object obj) {
    return super.equals(obj);
  }

  @Override
  protected final Object clone() throws CloneNotSupportedException {
    return super.clone();
  }

  @Nullable
  final Context getBuilderContext() {
    return mBuilderContext;
  }

  final void setBuilderContext(Context context) {
    mBuilderContext = context;
  }

  /**
   * @param <T> the type of this builder. Required to ensure methods defined here in the abstract
   *     class correctly return the type of the concrete subclass.
   */
  public abstract static class Builder<T extends Builder<T>> {

    protected final ResourceResolver mResourceResolver;
    private final ComponentContext mContext;
    private Component mComponent;

    protected Builder(
        ComponentContext c,
        @AttrRes int defStyleAttr,
        @StyleRes int defStyleRes,
        Component component) {
      mResourceResolver = c.getResourceResolver();
      mComponent = component;
      mContext = c;

      final Component owner = getOwner();
      if (owner != null) {
        mComponent.mOwnerGlobalKey = mContext.getGlobalKey();
      }

      if (defStyleAttr != 0 || defStyleRes != 0) {
        mComponent.getOrCreateCommonProps().setStyle(defStyleAttr, defStyleRes);
        try {
          component.loadStyle(c, defStyleAttr, defStyleRes);
        } catch (Exception e) {
          ComponentUtils.handleWithHierarchy(c, component, e);
        }
      }
      mComponent.setBuilderContext(c.getAndroidContext());
    }

    @ReturnsOwnership
    public abstract Component build();

    public abstract T getThis();

    protected abstract void setComponent(Component component);

    /**
     * Ports {@link androidx.core.view.ViewCompat#setAccessibilityHeading} into components world.
     * However, since the aforementioned ViewCompat's method is available only on API 19 and above,
     * calling this method on lower APIs will have no effect. On the legit versions, on the other
     * hand, calling this method will lead to the component being treated as a heading. The
     * AccessibilityHeading property allows accessibility services to help users navigate directly
     * from one heading to the next. See {@link
     * androidx.core.view.accessibility.AccessibilityNodeInfoCompat#setHeading} for more
     * information.
     *
     * <p>Default: false
     */
    public T accessibilityHeading(boolean isHeading) {
      mComponent.getOrCreateCommonProps().accessibilityHeading(isHeading);
      return getThis();
    }

    public T accessibilityRole(@Nullable @AccessibilityRole.AccessibilityRoleType String role) {
      mComponent.getOrCreateCommonProps().accessibilityRole(role);
      return getThis();
    }

    public T accessibilityRoleDescription(@Nullable CharSequence roleDescription) {
      mComponent.getOrCreateCommonProps().accessibilityRoleDescription(roleDescription);
      return getThis();
    }

    public T accessibilityRoleDescription(@StringRes int stringId) {
      return accessibilityRoleDescription(mContext.getResources().getString(stringId));
    }

    public T accessibilityRoleDescription(@StringRes int stringId, Object... formatArgs) {
      return accessibilityRoleDescription(mContext.getResources().getString(stringId, formatArgs));
    }

    /**
     * Controls how a child aligns in the cross direction, overriding the alignItems of the parent.
     * See <a
     * href="https://yogalayout.com/docs/align-items">https://yogalayout.com/docs/align-items</a>
     * for more information.
     *
     * <p>Default: {@link YogaAlign#AUTO}
     */
    public T alignSelf(@Nullable YogaAlign alignSelf) {
      mComponent.getOrCreateCommonProps().alignSelf(alignSelf);
      return getThis();
    }

    /** Sets the alpha (opacity) of this component. */
    public T alpha(float alpha) {
      mComponent.getOrCreateCommonProps().alpha(alpha);
      return getThis();
    }

    /**
     * Links a {@link DynamicValue} object ot the alpha value for this Component
     *
     * @param value controller for the alpha value
     */
    public T alpha(DynamicValue<Float> value) {
      mComponent.getOrCreateCommonDynamicProps().put(KEY_ALPHA, value);
      return getThis();
    }

    /**
     * Defined as the ratio between the width and the height of a node. See <a
     * href="https://yogalayout.com/docs/aspect-ratio">https://yogalayout.com/docs/aspect-ratio</a>
     * for more information
     */
    public T aspectRatio(float aspectRatio) {
      mComponent.getOrCreateCommonProps().aspectRatio(aspectRatio);
      return getThis();
    }

    /**
     * Set the background of this component. The background drawable can implement {@link
     * ComparableDrawable} for more efficient diffing while when drawables are remounted or updated.
     *
     * @see ComparableDrawable
     */
    public T background(@Nullable Drawable background) {
      mComponent.getOrCreateCommonProps().background(background);
      return getThis();
    }

    public T backgroundAttr(@AttrRes int resId, @DrawableRes int defaultResId) {
      return backgroundRes(mResourceResolver.resolveResIdAttr(resId, defaultResId));
    }

    public T backgroundAttr(@AttrRes int resId) {
      return backgroundAttr(resId, 0);
    }

    public T backgroundColor(@ColorInt int backgroundColor) {
      return background(ComparableColorDrawable.create(backgroundColor));
    }

    /**
     * Links a {@link DynamicValue} object to the background color value for this Component
     *
     * @param value controller for the background color value
     */
    public T backgroundColor(DynamicValue<Integer> value) {
      mComponent.getOrCreateCommonDynamicProps().put(KEY_BACKGROUND_COLOR, value);
      return getThis();
    }

    /**
     * Links a {@link DynamicValue} object to the background drawable for this Component
     *
     * @param value controller for the background drawable
     */
    public T backgroundDynamicDrawable(DynamicValue<? extends Drawable> value) {
      mComponent.getOrCreateCommonDynamicProps().put(KEY_BACKGROUND_DRAWABLE, value);
      return getThis();
    }

    public T backgroundRes(@DrawableRes int resId) {
      if (resId == 0) {
        return background(null);
      }

      return background(ContextCompat.getDrawable(mContext.getAndroidContext(), resId));
    }

    public T border(@Nullable Border border) {
      mComponent.getOrCreateCommonProps().border(border);
      return getThis();
    }

    public T clickHandler(@Nullable EventHandler<ClickEvent> clickHandler) {
      mComponent.getOrCreateCommonProps().clickHandler(clickHandler);
      return getThis();
    }

    public T clickable(boolean isClickable) {
      mComponent.getOrCreateCommonProps().clickable(isClickable);
      return getThis();
    }

    /**
     * Ports {@link android.view.ViewGroup#setClipChildren(boolean)} into components world. However,
     * there is no guarantee that child of this component would be translated into direct view child
     * in the resulting view hierarchy.
     *
     * @param clipChildren true to clip children to their bounds. False allows each child to draw
     *     outside of its own bounds within the parent, it doesn't allow children to draw outside of
     *     the parent itself.
     */
    public T clipChildren(boolean clipChildren) {
      mComponent.getOrCreateCommonProps().clipChildren(clipChildren);
      return getThis();
    }

    public T clipToOutline(boolean clipToOutline) {
      mComponent.getOrCreateCommonProps().clipToOutline(clipToOutline);
      return getThis();
    }

    public T contentDescription(@Nullable CharSequence contentDescription) {
      mComponent.getOrCreateCommonProps().contentDescription(contentDescription);
      return getThis();
    }

    public T contentDescription(@StringRes int stringId) {
      return contentDescription(mContext.getAndroidContext().getResources().getString(stringId));
    }

    public T contentDescription(@StringRes int stringId, Object... formatArgs) {
      return contentDescription(
          mContext.getAndroidContext().getResources().getString(stringId, formatArgs));
    }

    public T dispatchPopulateAccessibilityEventHandler(
        @Nullable
            EventHandler<DispatchPopulateAccessibilityEventEvent>
                dispatchPopulateAccessibilityEventHandler) {
      mComponent
          .getOrCreateCommonProps()
          .dispatchPopulateAccessibilityEventHandler(dispatchPopulateAccessibilityEventHandler);
      return getThis();
    }

    /**
     * If true, component duplicates its drawable state (focused, pressed, etc.) from the direct
     * parent.
     *
     * <p>In the following example, when {@code Row} gets pressed state, its child {@code
     * OtherStatefulDrawable} will get that pressed state within itself too:
     *
     * <pre>{@code
     * Row.create(c)
     *     .drawable(stateListDrawable)
     *     .clickable(true)
     *     .child(
     *         OtherStatefulDrawable.create(c)
     *             .duplicateParentState(true))
     * }</pre>
     *
     * @see android.view.View#setDuplicateParentStateEnabled(boolean)
     */
    public T duplicateParentState(boolean duplicateParentState) {
      mComponent.getOrCreateCommonProps().duplicateParentState(duplicateParentState);
      return getThis();
    }

    /**
     * If true, component applies all of its children's drawable states (focused, pressed, etc.) to
     * itself.
     *
     * <p>In the following example, when {@code OtherStatefulDrawable} gets pressed state, its
     * parent {@code Row} will also get that pressed state within itself:
     *
     * <pre>{@code
     * Row.create(c)
     *     .drawable(stateListDrawable)
     *     .duplicateChildrenStates(true)
     *     .child(
     *         OtherStatefulDrawable.create(c)
     *             .clickable(true))
     * }</pre>
     *
     * @see android.view.ViewGroup#setAddStatesFromChildren
     */
    public T duplicateChildrenStates(boolean duplicateChildrenStates) {
      mComponent.getOrCreateCommonProps().duplicateChildrenStates(duplicateChildrenStates);
      return getThis();
    }

    public T enabled(boolean isEnabled) {
      mComponent.getOrCreateCommonProps().enabled(isEnabled);
      return getThis();
    }

    /**
     * Sets flexGrow, flexShrink, and flexBasis at the same time.
     *
     * <p>When flex is a positive number, it makes the component flexible and it will be sized
     * proportional to its flex value. So a component with flex set to 2 will take twice the space
     * as a component with flex set to 1.
     *
     * <p>When flex is 0, the component is sized according to width and height and it is inflexible.
     *
     * <p>When flex is -1, the component is normally sized according width and height. However, if
     * there's not enough space, the component will shrink to its minWidth and minHeight.
     *
     * <p>See <a href="https://yogalayout.com/docs/flex">https://yogalayout.com/docs/flex</a> for
     * more information.
     *
     * <p>Default: 0
     */
    public T flex(float flex) {
      mComponent.getOrCreateCommonProps().flex(flex);
      return getThis();
    }

    /** @see #flexBasisPx */
    public T flexBasisAttr(@AttrRes int resId, @DimenRes int defaultResId) {
      return flexBasisPx(mResourceResolver.resolveDimenSizeAttr(resId, defaultResId));
    }

    /** @see #flexBasisPx */
    public T flexBasisAttr(@AttrRes int resId) {
      return flexBasisAttr(resId, 0);
    }

    /** @see #flexBasisPx */
    public T flexBasisDip(@Dimension(unit = DP) float flexBasis) {
      return flexBasisPx(mResourceResolver.dipsToPixels(flexBasis));
    }

    /**
     * @see #flexBasisPx
     * @param percent a value between 0 and 100.
     */
    public T flexBasisPercent(float percent) {
      mComponent.getOrCreateCommonProps().flexBasisPercent(percent);
      return getThis();
    }

    /**
     * The FlexBasis property is an axis-independent way of providing the default size of an item on
     * the main axis. Setting the FlexBasis of a child is similar to setting the Width of that child
     * if its parent is a container with FlexDirection = row or setting the Height of a child if its
     * parent is a container with FlexDirection = column. The FlexBasis of an item is the default
     * size of that item, the size of the item before any FlexGrow and FlexShrink calculations are
     * performed. See <a
     * href="https://yogalayout.com/docs/flex">https://yogalayout.com/docs/flex</a> for more
     * information.
     *
     * <p>Default: 0
     */
    public T flexBasisPx(@Px int flexBasis) {
      mComponent.getOrCreateCommonProps().flexBasisPx(flexBasis);
      return getThis();
    }

    /** @see #flexBasisPx */
    public T flexBasisRes(@DimenRes int resId) {
      return flexBasisPx(mResourceResolver.resolveDimenSizeRes(resId));
    }

    /**
     * If the sum of childrens' main axis dimensions is less than the minimum size, how much should
     * this component grow? This value represents the "flex grow factor" and determines how much
     * this component should grow along the main axis in relation to any other flexible children.
     * See <a href="https://yogalayout.com/docs/flex">https://yogalayout.com/docs/flex</a> for more
     * information.
     *
     * <p>Default: 0
     */
    public T flexGrow(float flexGrow) {
      mComponent.getOrCreateCommonProps().flexGrow(flexGrow);
      return getThis();
    }

    /**
     * The FlexShrink property describes how to shrink children along the main axis in the case that
     * the total size of the children overflow the size of the container on the main axis. See <a
     * href="https://yogalayout.com/docs/flex">https://yogalayout.com/docs/flex</a> for more
     * information.
     *
     * <p>Default: 1
     */
    public T flexShrink(float flexShrink) {
      mComponent.getOrCreateCommonProps().flexShrink(flexShrink);
      return getThis();
    }

    public T focusChangeHandler(@Nullable EventHandler<FocusChangedEvent> focusChangeHandler) {
      mComponent.getOrCreateCommonProps().focusChangeHandler(focusChangeHandler);
      return getThis();
    }

    public T focusable(boolean isFocusable) {
      mComponent.getOrCreateCommonProps().focusable(isFocusable);
      return getThis();
    }

    public T focusedHandler(@Nullable EventHandler<FocusedVisibleEvent> focusedHandler) {
      mComponent.getOrCreateCommonProps().focusedHandler(focusedHandler);
      return getThis();
    }

    /**
     * Set the foreground of this component. The foreground drawable must extend {@link
     * ComparableDrawable} for more efficient diffing while when drawables are remounted or updated.
     * If the drawable does not extend {@link ComparableDrawable} then create a new class which
     * extends {@link ComparableDrawable} and implement the {@link
     * ComparableDrawable#isEquivalentTo(ComparableDrawable)}.
     *
     * @see ComparableDrawable
     */
    public T foreground(@Nullable Drawable foreground) {
      mComponent.getOrCreateCommonProps().foreground(foreground);
      return getThis();
    }

    public T foregroundAttr(@AttrRes int resId, @DrawableRes int defaultResId) {
      return foregroundRes(mResourceResolver.resolveResIdAttr(resId, defaultResId));
    }

    public T foregroundAttr(@AttrRes int resId) {
      return foregroundAttr(resId, 0);
    }

    public T foregroundColor(@ColorInt int foregroundColor) {
      return foreground(ComparableColorDrawable.create(foregroundColor));
    }

    public T foregroundColor(DynamicValue<Integer> value) {
      mComponent.getOrCreateCommonDynamicProps().put(KEY_FOREGROUND_COLOR, value);
      return getThis();
    }

    public T foregroundRes(@DrawableRes int resId) {
      if (resId == 0) {
        return foreground(null);
      }

      return foreground(ContextCompat.getDrawable(mContext.getAndroidContext(), resId));
    }

    public T fullImpressionHandler(
        @Nullable EventHandler<FullImpressionVisibleEvent> fullImpressionHandler) {
      mComponent.getOrCreateCommonProps().fullImpressionHandler(fullImpressionHandler);
      return getThis();
    }

    /**
     * @return the {@link ComponentContext} for this {@link Builder}, useful for Kotlin DSL. Will be
     *     null if the Builder was already used to {@link #build()} a component.
     */
    @Nullable
    public ComponentContext getContext() {
      return mContext;
    }

    public T handle(@Nullable Handle handle) {
      mComponent.setHandle(handle);
      return getThis();
    }

    @Deprecated
    public boolean hasBackgroundSet() {
      return mComponent.mCommonProps != null && mComponent.mCommonProps.getBackground() != null;
    }

    public boolean hasClickHandlerSet() {
      return mComponent.hasClickHandlerSet();
    }

    /** @see #heightPx */
    public T heightAttr(@AttrRes int resId, @DimenRes int defaultResId) {
      return heightPx(mResourceResolver.resolveDimenSizeAttr(resId, defaultResId));
    }

    /** @see #heightPx */
    public T heightAttr(@AttrRes int resId) {
      return heightAttr(resId, 0);
    }

    /** @see #heightPx */
    public T heightDip(@Dimension(unit = DP) float height) {
      return heightPx(mResourceResolver.dipsToPixels(height));
    }

    /**
     * Sets the height of the Component to be a percentage of its parent's height. Note that if the
     * parent has unspecified height (e.g. it is a RecyclerView), then setting this will have no
     * effect.
     *
     * @see #heightPx
     * @param percent a value between 0 and 100.
     */
    public T heightPercent(float percent) {
      mComponent.getOrCreateCommonProps().heightPercent(percent);
      return getThis();
    }

    /**
     * Specifies the height of the element's content area. See <a
     * href="https://yogalayout.com/docs/width-height">https://yogalayout.com/docs/width-height</a>
     * for more information
     */
    public T heightPx(@Px int height) {
      mComponent.getOrCreateCommonProps().heightPx(height);
      return getThis();
    }

    /** @see #heightPx */
    public T heightRes(@DimenRes int resId) {
      return heightPx(mResourceResolver.resolveDimenSizeRes(resId));
    }

    public T importantForAccessibility(int importantForAccessibility) {
      mComponent.getOrCreateCommonProps().importantForAccessibility(importantForAccessibility);
      return getThis();
    }

    public T interceptTouchHandler(
        @Nullable EventHandler<InterceptTouchEvent> interceptTouchHandler) {
      mComponent.getOrCreateCommonProps().interceptTouchHandler(interceptTouchHandler);
      return getThis();
    }

    public T invisibleHandler(@Nullable EventHandler<InvisibleEvent> invisibleHandler) {
      mComponent.getOrCreateCommonProps().invisibleHandler(invisibleHandler);
      return getThis();
    }

    public T isReferenceBaseline(boolean isReferenceBaseline) {
      mComponent.getOrCreateCommonProps().isReferenceBaseline(isReferenceBaseline);
      return getThis();
    }

    /** Set a key on the component that is local to its parent. */
    public T key(@Nullable String key) {
      if (key == null) {
        final String componentName =
            mContext.getComponentScope() != null
                ? mContext.getComponentScope().getSimpleName()
                : "unknown component";
        final String message =
            "Setting a null key from "
                + componentName
                + " which is usually a mistake! If it is not, explicitly set the String 'null'";
        ComponentsReporter.emitMessage(ComponentsReporter.LogLevel.ERROR, NULL_KEY_SET, message);
        key = "null";
      }
      mComponent.setKey(key);
      return getThis();
    }

    /**
     * The RTL/LTR direction of components and text. Determines whether {@link YogaEdge#START} and
     * {@link YogaEdge#END} will resolve to the left or right side, among other things. INHERIT
     * indicates this setting will be inherited from this component's parent.
     *
     * <p>Default: {@link YogaDirection#INHERIT}
     */
    public T layoutDirection(@Nullable YogaDirection layoutDirection) {
      mComponent.getOrCreateCommonProps().layoutDirection(layoutDirection);
      return getThis();
    }

    public T longClickHandler(@Nullable EventHandler<LongClickEvent> longClickHandler) {
      mComponent.getOrCreateCommonProps().longClickHandler(longClickHandler);
      return getThis();
    }

    /** @see #marginPx */
    public T marginAttr(@Nullable YogaEdge edge, @AttrRes int resId, @DimenRes int defaultResId) {
      return marginPx(edge, mResourceResolver.resolveDimenSizeAttr(resId, defaultResId));
    }

    /** @see #marginPx */
    public T marginAttr(@Nullable YogaEdge edge, @AttrRes int resId) {
      return marginAttr(edge, resId, 0);
    }

    /** @see #marginPx */
    public T marginAuto(@Nullable YogaEdge edge) {
      mComponent.getOrCreateCommonProps().marginAuto(edge);
      return getThis();
    }

    /** @see #marginPx */
    public T marginDip(@Nullable YogaEdge edge, @Dimension(unit = DP) float margin) {
      return marginPx(edge, mResourceResolver.dipsToPixels(margin));
    }

    /**
     * @see #marginPx
     * @param percent a value between 0 and 100.
     */
    public T marginPercent(@Nullable YogaEdge edge, float percent) {
      mComponent.getOrCreateCommonProps().marginPercent(edge, percent);
      return getThis();
    }

    /**
     * Effects the spacing around the outside of a node. A node with margin will offset itself from
     * the bounds of its parent but also offset the location of any siblings. See <a
     * href="https://yogalayout.com/docs/margins-paddings-borders">https://yogalayout.com/docs/margins-paddings-borders</a>
     * for more information
     */
    public T marginPx(@Nullable YogaEdge edge, @Px int margin) {
      mComponent.getOrCreateCommonProps().marginPx(edge, margin);
      return getThis();
    }

    /** @see #marginPx */
    public T marginRes(@Nullable YogaEdge edge, @DimenRes int resId) {
      return marginPx(edge, mResourceResolver.resolveDimenSizeRes(resId));
    }

    /** @see #minWidthPx */
    public T maxHeightAttr(@AttrRes int resId, @DimenRes int defaultResId) {
      return maxHeightPx(mResourceResolver.resolveDimenSizeAttr(resId, defaultResId));
    }

    /** @see #minWidthPx */
    public T maxHeightAttr(@AttrRes int resId) {
      return maxHeightAttr(resId, 0);
    }

    /** @see #minWidthPx */
    public T maxHeightDip(@Dimension(unit = DP) float maxHeight) {
      return maxHeightPx(mResourceResolver.dipsToPixels(maxHeight));
    }

    /**
     * @see #minWidthPx
     * @param percent a value between 0 and 100.
     */
    public T maxHeightPercent(float percent) {
      mComponent.getOrCreateCommonProps().maxHeightPercent(percent);
      return getThis();
    }

    /** @see #minWidthPx */
    public T maxHeightPx(@Px int maxHeight) {
      mComponent.getOrCreateCommonProps().maxHeightPx(maxHeight);
      return getThis();
    }

    /** @see #minWidthPx */
    public T maxHeightRes(@DimenRes int resId) {
      return maxHeightPx(mResourceResolver.resolveDimenSizeRes(resId));
    }

    /** @see #minWidthPx */
    public T maxWidthAttr(@AttrRes int resId, @DimenRes int defaultResId) {
      return maxWidthPx(mResourceResolver.resolveDimenSizeAttr(resId, defaultResId));
    }

    /** @see #minWidthPx */
    public T maxWidthAttr(@AttrRes int resId) {
      return maxWidthAttr(resId, 0);
    }

    /** @see #minWidthPx */
    public T maxWidthDip(@Dimension(unit = DP) float maxWidth) {
      return maxWidthPx(mResourceResolver.dipsToPixels(maxWidth));
    }

    /**
     * @see #minWidthPx
     * @param percent a value between 0 and 100.
     */
    public T maxWidthPercent(float percent) {
      mComponent.getOrCreateCommonProps().maxWidthPercent(percent);
      return getThis();
    }

    /** @see #minWidthPx */
    public T maxWidthPx(@Px int maxWidth) {
      mComponent.getOrCreateCommonProps().maxWidthPx(maxWidth);
      return getThis();
    }

    /** @see #minWidthPx */
    public T maxWidthRes(@DimenRes int resId) {
      return maxWidthPx(mResourceResolver.resolveDimenSizeRes(resId));
    }

    /** @see #minWidthPx */
    public T minHeightAttr(@AttrRes int resId, @DimenRes int defaultResId) {
      return minHeightPx(mResourceResolver.resolveDimenSizeAttr(resId, defaultResId));
    }

    /** @see #minWidthPx */
    public T minHeightAttr(@AttrRes int resId) {
      return minHeightAttr(resId, 0);
    }

    /** @see #minWidthPx */
    public T minHeightDip(@Dimension(unit = DP) float minHeight) {
      return minHeightPx(mResourceResolver.dipsToPixels(minHeight));
    }

    /**
     * @see #minWidthPx
     * @param percent a value between 0 and 100.
     */
    public T minHeightPercent(float percent) {
      mComponent.getOrCreateCommonProps().minHeightPercent(percent);
      return getThis();
    }

    /** @see #minWidthPx */
    public T minHeightPx(@Px int minHeight) {
      mComponent.getOrCreateCommonProps().minHeightPx(minHeight);
      return getThis();
    }

    /** @see #minWidthPx */
    public T minHeightRes(@DimenRes int resId) {
      return minHeightPx(mResourceResolver.resolveDimenSizeRes(resId));
    }

    /** @see #minWidthPx */
    public T minWidthAttr(@AttrRes int resId, @DimenRes int defaultResId) {
      return minWidthPx(mResourceResolver.resolveDimenSizeAttr(resId, defaultResId));
    }

    /** @see #minWidthPx */
    public T minWidthAttr(@AttrRes int resId) {
      return minWidthAttr(resId, 0);
    }

    /** @see #minWidthPx */
    public T minWidthDip(@Dimension(unit = DP) float minWidth) {
      return minWidthPx(mResourceResolver.dipsToPixels(minWidth));
    }

    /**
     * @see #minWidthPx
     * @param percent a value between 0 and 100.
     */
    public T minWidthPercent(float percent) {
      mComponent.getOrCreateCommonProps().minWidthPercent(percent);
      return getThis();
    }

    /**
     * This property has higher priority than all other properties and will always be respected. See
     * <a href="https://yogalayout.com/docs/min-max/">https://yogalayout.com/docs/min-max/</a> for
     * more information
     */
    public T minWidthPx(@Px int minWidth) {
      mComponent.getOrCreateCommonProps().minWidthPx(minWidth);
      return getThis();
    }

    /** @see #minWidthPx */
    public T minWidthRes(@DimenRes int resId) {
      return minWidthPx(mResourceResolver.resolveDimenSizeRes(resId));
    }

    public T onInitializeAccessibilityEventHandler(
        @Nullable
            EventHandler<OnInitializeAccessibilityEventEvent>
                onInitializeAccessibilityEventHandler) {
      mComponent
          .getOrCreateCommonProps()
          .onInitializeAccessibilityEventHandler(onInitializeAccessibilityEventHandler);
      return getThis();
    }

    public T onInitializeAccessibilityNodeInfoHandler(
        @Nullable
            EventHandler<OnInitializeAccessibilityNodeInfoEvent>
                onInitializeAccessibilityNodeInfoHandler) {
      mComponent
          .getOrCreateCommonProps()
          .onInitializeAccessibilityNodeInfoHandler(onInitializeAccessibilityNodeInfoHandler);
      return getThis();
    }

    public T onPopulateAccessibilityEventHandler(
        @Nullable
            EventHandler<OnPopulateAccessibilityEventEvent> onPopulateAccessibilityEventHandler) {
      mComponent
          .getOrCreateCommonProps()
          .onPopulateAccessibilityEventHandler(onPopulateAccessibilityEventHandler);
      return getThis();
    }

    public T onRequestSendAccessibilityEventHandler(
        @Nullable
            EventHandler<OnRequestSendAccessibilityEventEvent>
                onRequestSendAccessibilityEventHandler) {
      mComponent
          .getOrCreateCommonProps()
          .onRequestSendAccessibilityEventHandler(onRequestSendAccessibilityEventHandler);
      return getThis();
    }

    public T outlineProvider(@Nullable ViewOutlineProvider outlineProvider) {
      mComponent.getOrCreateCommonProps().outlineProvider(outlineProvider);
      return getThis();
    }

    /** @see #paddingPx */
    public T paddingAttr(@Nullable YogaEdge edge, @AttrRes int resId, @DimenRes int defaultResId) {
      return paddingPx(edge, mResourceResolver.resolveDimenSizeAttr(resId, defaultResId));
    }

    /** @see #paddingPx */
    public T paddingAttr(@Nullable YogaEdge edge, @AttrRes int resId) {
      return paddingAttr(edge, resId, 0);
    }

    /** @see #paddingPx */
    public T paddingDip(@Nullable YogaEdge edge, @Dimension(unit = DP) float padding) {
      return paddingPx(edge, mResourceResolver.dipsToPixels(padding));
    }

    /**
     * @see #paddingPx
     * @param percent a value between 0 and 100.
     */
    public T paddingPercent(@Nullable YogaEdge edge, float percent) {
      mComponent.getOrCreateCommonProps().paddingPercent(edge, percent);
      return getThis();
    }

    /**
     * Affects the size of the node it is applied to. Padding will not add to the total size of an
     * element if it has an explicit size set. See <a
     * href="https://yogalayout.com/docs/margins-paddings-borders">https://yogalayout.com/docs/margins-paddings-borders</a>
     * for more information
     */
    public T paddingPx(@Nullable YogaEdge edge, @Px int padding) {
      mComponent.getOrCreateCommonProps().paddingPx(edge, padding);
      return getThis();
    }

    /** @see #paddingPx */
    public T paddingRes(@Nullable YogaEdge edge, @DimenRes int resId) {
      return paddingPx(edge, mResourceResolver.resolveDimenSizeRes(resId));
    }

    public T performAccessibilityActionHandler(
        @Nullable EventHandler<PerformAccessibilityActionEvent> performAccessibilityActionHandler) {
      mComponent
          .getOrCreateCommonProps()
          .performAccessibilityActionHandler(performAccessibilityActionHandler);
      return getThis();
    }

    /** @see #positionPx */
    public T positionAttr(@Nullable YogaEdge edge, @AttrRes int resId, @DimenRes int defaultResId) {
      return positionPx(edge, mResourceResolver.resolveDimenSizeAttr(resId, defaultResId));
    }

    /** @see #positionPx */
    public T positionAttr(@Nullable YogaEdge edge, @AttrRes int resId) {
      return positionAttr(edge, resId, 0);
    }

    /** @see #positionPx */
    public T positionDip(@Nullable YogaEdge edge, @Dimension(unit = DP) float position) {
      return positionPx(edge, mResourceResolver.dipsToPixels(position));
    }

    /**
     * @see #positionPx
     * @param percent a value between 0 and 100.
     */
    public T positionPercent(@Nullable YogaEdge edge, float percent) {
      mComponent.getOrCreateCommonProps().positionPercent(edge, percent);
      return getThis();
    }

    /**
     * When used in combination with {@link #positionType} of {@link YogaPositionType#ABSOLUTE},
     * allows the component to specify how it should be positioned within its parent. See <a
     * href="https://yogalayout.com/docs/absolute-relative-layout">https://yogalayout.com/docs/absolute-relative-layout</a>
     * for more information.
     */
    public T positionPx(@Nullable YogaEdge edge, @Px int position) {
      mComponent.getOrCreateCommonProps().positionPx(edge, position);
      return getThis();
    }

    /** @see #positionPx */
    public T positionRes(@Nullable YogaEdge edge, @DimenRes int resId) {
      return positionPx(edge, mResourceResolver.resolveDimenSizeRes(resId));
    }

    /**
     * Controls how this component will be positioned within its parent. See <a
     * href="https://yogalayout.com/docs/absolute-relative-layout">https://yogalayout.com/docs/absolute-relative-layout</a>
     * for more details.
     *
     * <p>Default: {@link YogaPositionType#RELATIVE}
     */
    public T positionType(@Nullable YogaPositionType positionType) {
      mComponent.getOrCreateCommonProps().positionType(positionType);
      return getThis();
    }

    /**
     * Sets the degree that this component is rotated around the pivot point. Increasing the value
     * results in clockwise rotation. By default, the pivot point is centered on the component.
     */
    public T rotation(float rotation) {
      mComponent.getOrCreateCommonProps().rotation(rotation);
      return getThis();
    }

    /**
     * Links a {@link DynamicValue} object to the rotation value for this Component
     *
     * @param rotation controller for the rotation value
     */
    public T rotation(DynamicValue<Float> rotation) {
      mComponent.getOrCreateCommonDynamicProps().put(KEY_ROTATION, rotation);
      return getThis();
    }

    /**
     * Sets the degree that this component is rotated around the horizontal axis through the pivot
     * point.
     */
    public T rotationX(float rotationX) {
      mComponent.getOrCreateCommonProps().rotationX(rotationX);
      return getThis();
    }

    /**
     * Sets the degree that this component is rotated around the vertical axis through the pivot
     * point.
     */
    public T rotationY(float rotationY) {
      mComponent.getOrCreateCommonProps().rotationY(rotationY);
      return getThis();
    }

    /**
     * Sets the scale (scaleX and scaleY) on this component. This is mostly relevant for animations
     * and being able to animate size changes. Otherwise for non-animation usecases, you should use
     * the standard layout properties to control the size of your component.
     */
    public T scale(float scale) {
      mComponent.getOrCreateCommonProps().scale(scale);
      return getThis();
    }

    /**
     * Links a {@link DynamicValue} object to the scaleX value for this Component
     *
     * @param value controller for the scaleX value
     */
    public T scaleX(DynamicValue<Float> value) {
      mComponent.getOrCreateCommonDynamicProps().put(KEY_SCALE_X, value);
      return getThis();
    }

    /**
     * Links a {@link DynamicValue} object to the scaleY value for this Component
     *
     * @param value controller for the scaleY value
     */
    public T scaleY(DynamicValue<Float> value) {
      mComponent.getOrCreateCommonDynamicProps().put(KEY_SCALE_Y, value);
      return getThis();
    }

    public T selected(boolean isSelected) {
      mComponent.getOrCreateCommonProps().selected(isSelected);
      return getThis();
    }

    public T sendAccessibilityEventHandler(
        @Nullable EventHandler<SendAccessibilityEventEvent> sendAccessibilityEventHandler) {
      mComponent
          .getOrCreateCommonProps()
          .sendAccessibilityEventHandler(sendAccessibilityEventHandler);
      return getThis();
    }

    public T sendAccessibilityEventUncheckedHandler(
        @Nullable
            EventHandler<SendAccessibilityEventUncheckedEvent>
                sendAccessibilityEventUncheckedHandler) {
      mComponent
          .getOrCreateCommonProps()
          .sendAccessibilityEventUncheckedHandler(sendAccessibilityEventUncheckedHandler);
      return getThis();
    }

    public T shadowElevationAttr(@AttrRes int resId, @DimenRes int defaultResId) {
      return shadowElevationPx(mResourceResolver.resolveDimenSizeAttr(resId, defaultResId));
    }

    public T shadowElevationAttr(@AttrRes int resId) {
      return shadowElevationAttr(resId, 0);
    }

    public T shadowElevationDip(@Dimension(unit = DP) float shadowElevation) {
      return shadowElevationPx(mResourceResolver.dipsToPixels(shadowElevation));
    }

    /**
     * Shadow elevation and outline provider methods are only functional on {@link
     * android.os.Build.VERSION_CODES#LOLLIPOP} and above.
     */
    public T shadowElevationPx(float shadowElevation) {
      mComponent.getOrCreateCommonProps().shadowElevationPx(shadowElevation);
      return getThis();
    }

    public T shadowElevationRes(@DimenRes int resId) {
      return shadowElevationPx(mResourceResolver.resolveDimenSizeRes(resId));
    }

    /**
     * Links a {@link DynamicValue} object to the elevation value for this Component
     *
     * @param value controller for the elevation value
     */
    public T shadowElevation(DynamicValue<Float> value) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        mComponent.getOrCreateCommonDynamicProps().put(KEY_ELEVATION, value);
      }
      return getThis();
    }

    /**
     * Ports {@link android.view.View#setStateListAnimator(android.animation.StateListAnimator)}
     * into components world. However, since the aforementioned view's method is available only on
     * API 21 and above, calling this method on lower APIs will have no effect. On the legit
     * versions, on the other hand, calling this method will lead to the component being wrapped
     * into a view
     */
    public T stateListAnimator(@Nullable StateListAnimator stateListAnimator) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        mComponent.getOrCreateCommonProps().stateListAnimator(stateListAnimator);
      }
      return getThis();
    }

    /**
     * Ports {@link android.view.View#setStateListAnimator(android.animation.StateListAnimator)}
     * into components world. However, since the aforementioned view's method is available only on
     * API 21 and above, calling this method on lower APIs will have no effect. On the legit
     * versions, on the other hand, calling this method will lead to the component being wrapped
     * into a view
     */
    public T stateListAnimatorRes(@DrawableRes int resId) {
      if (Build.VERSION.SDK_INT >= 26) {
        // We cannot do it on the versions prior to Android 8.0 since there is a possible race
        // condition when loading state list animators, thus we will avoid doing it off the UI
        // thread
        return stateListAnimator(
            AnimatorInflater.loadStateListAnimator(mContext.getAndroidContext(), resId));
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        mComponent.getOrCreateCommonProps().stateListAnimatorRes(resId);
      }
      return getThis();
    }

    public T testKey(@Nullable String testKey) {
      mComponent.getOrCreateCommonProps().testKey(testKey);
      return getThis();
    }

    public T componentTag(@Nullable Object componentTag) {
      mComponent.getOrCreateCommonProps().componentTag(componentTag);
      return getThis();
    }

    public T touchExpansionAttr(
        @Nullable YogaEdge edge, @AttrRes int resId, @DimenRes int defaultResId) {
      return touchExpansionPx(edge, mResourceResolver.resolveDimenSizeAttr(resId, defaultResId));
    }

    public T touchExpansionAttr(@Nullable YogaEdge edge, @AttrRes int resId) {
      return touchExpansionAttr(edge, resId, 0);
    }

    public T touchExpansionDip(
        @Nullable YogaEdge edge, @Dimension(unit = DP) float touchExpansion) {
      return touchExpansionPx(edge, mResourceResolver.dipsToPixels(touchExpansion));
    }

    public T touchExpansionPx(@Nullable YogaEdge edge, @Px int touchExpansion) {
      mComponent.getOrCreateCommonProps().touchExpansionPx(edge, touchExpansion);
      return getThis();
    }

    public T touchExpansionRes(@Nullable YogaEdge edge, @DimenRes int resId) {
      return touchExpansionPx(edge, mResourceResolver.resolveDimenSizeRes(resId));
    }

    public T touchHandler(@Nullable EventHandler<TouchEvent> touchHandler) {
      mComponent.getOrCreateCommonProps().touchHandler(touchHandler);
      return getThis();
    }

    public T transitionKey(@Nullable String key) {
      mComponent.getOrCreateCommonProps().transitionKey(key, mComponent.mOwnerGlobalKey);
      if (mComponent.getOrCreateCommonProps().getTransitionKeyType() == null) {
        // If TransitionKeyType isn't set, set to default type
        transitionKeyType(Transition.DEFAULT_TRANSITION_KEY_TYPE);
      }
      return getThis();
    }

    public T transitionName(@Nullable String transitionName) {
      mComponent.getOrCreateCommonProps().transitionName(transitionName);
      return getThis();
    }

    public T transitionKeyType(Transition.TransitionKeyType type) {
      if (type == null) {
        throw new IllegalArgumentException("TransitionKeyType must not be null");
      }
      mComponent.getOrCreateCommonProps().transitionKeyType(type);
      return getThis();
    }

    /**
     * Links a {@link DynamicValue} object to the translationX value for this Component
     *
     * @param value controller for the translationY value
     */
    public T translationX(DynamicValue<Float> value) {
      mComponent.getOrCreateCommonDynamicProps().put(KEY_TRANSLATION_X, value);
      return getThis();
    }

    /**
     * Links a {@link DynamicValue} object to the translationY value for this Component
     *
     * @param value controller for the translationY value
     */
    public T translationY(DynamicValue<Float> value) {
      mComponent.getOrCreateCommonDynamicProps().put(KEY_TRANSLATION_Y, value);
      return getThis();
    }

    /**
     * Links a {@link DynamicValue} object to a Key for this Component
     *
     * @param key to access metadata for the object
     * @param value value stored at {@link key}
     */
    public synchronized <K, V> T metadata(K key, V value) {
      if (mComponent.mMetadata == null) {
        mComponent.mMetadata = new ArrayMap<>();
      }
      mComponent.mMetadata.put(key, value);
      return getThis();
    }

    public T unfocusedHandler(@Nullable EventHandler<UnfocusedVisibleEvent> unfocusedHandler) {
      mComponent.getOrCreateCommonProps().unfocusedHandler(unfocusedHandler);
      return getThis();
    }

    /**
     * When set to true, overrides the default behaviour of baseline calculation and uses height of
     * component as baseline. By default the baseline of a component is the baseline of first child
     * of component (If the component does not have any child then baseline is height of the
     * component)
     */
    public T useHeightAsBaseline(boolean useHeightAsBaseline) {
      mComponent.getOrCreateCommonProps().useHeightAsBaseline(useHeightAsBaseline);
      return getThis();
    }

    public T viewTag(@Nullable Object viewTag) {
      mComponent.getOrCreateCommonProps().viewTag(viewTag);
      return getThis();
    }

    public T viewTags(@Nullable SparseArray<Object> viewTags) {
      mComponent.getOrCreateCommonProps().viewTags(viewTags);
      return getThis();
    }

    public T visibilityChangedHandler(
        @Nullable EventHandler<VisibilityChangedEvent> visibilityChangedHandler) {
      mComponent.getOrCreateCommonProps().visibilityChangedHandler(visibilityChangedHandler);
      return getThis();
    }

    public T visibleHandler(@Nullable EventHandler<VisibleEvent> visibleHandler) {
      mComponent.getOrCreateCommonProps().visibleHandler(visibleHandler);
      return getThis();
    }

    public T visibleHeightRatio(float visibleHeightRatio) {
      mComponent.getOrCreateCommonProps().visibleHeightRatio(visibleHeightRatio);
      return getThis();
    }

    public T visibleWidthRatio(float visibleWidthRatio) {
      mComponent.getOrCreateCommonProps().visibleWidthRatio(visibleWidthRatio);
      return getThis();
    }

    /** @see #widthPx */
    public T widthAttr(@AttrRes int resId, @DimenRes int defaultResId) {
      return widthPx(mResourceResolver.resolveDimenSizeAttr(resId, defaultResId));
    }

    /** @see #widthPx */
    public T widthAttr(@AttrRes int resId) {
      return widthAttr(resId, 0);
    }

    /** @see #widthPx */
    public T widthDip(@Dimension(unit = DP) float width) {
      return widthPx(mResourceResolver.dipsToPixels(width));
    }

    /**
     * Sets the width of the Component to be a percentage of its parent's width. Note that if the
     * parent has unspecified width (e.g. it is an HScroll), then setting this will have no effect.
     *
     * @see #widthPx
     * @param percent a value between 0 and 100.
     */
    public T widthPercent(float percent) {
      mComponent.getOrCreateCommonProps().widthPercent(percent);
      return getThis();
    }

    /**
     * Specifies the width of the element's content area. See <a
     * href="https://yogalayout.com/docs/width-height">https://yogalayout.com/docs/width-height</a>
     * for more information
     */
    public T widthPx(@Px int width) {
      mComponent.getOrCreateCommonProps().widthPx(width);
      return getThis();
    }

    /** @see #widthPx */
    public T widthRes(@DimenRes int resId) {
      return widthPx(mResourceResolver.resolveDimenSizeRes(resId));
    }

    public T wrapInView() {
      mComponent.getOrCreateCommonProps().wrapInView();
      return getThis();
    }

    public T layerType(@LayerType int type, @Nullable Paint paint) {
      mComponent.getOrCreateCommonProps().layerType(type, paint);
      return getThis();
    }

    private @Nullable Component getOwner() {
      return mContext.getComponentScope();
    }

    /**
     * Note: This is exposed for backwards compatibility with the Kotlin API to allow applying
     * common props via Style without moving Style into Java. Use with caution since at this point
     * the Component is still being built and should not escape the Builder.
     */
    final Component getComponent() {
      return mComponent;
    }

    /**
     * Checks that all the required props are supplied, and if not throws a useful exception
     *
     * @param requiredPropsCount expected number of props
     * @param required the bit set that identifies which props have been supplied
     * @param requiredPropsNames the names of all props used for a useful error message
     */
    protected static void checkArgs(
        int requiredPropsCount, BitSet required, String[] requiredPropsNames) {
      if (required != null && required.nextClearBit(0) < requiredPropsCount) {
        List<String> missingProps = new ArrayList<>();
        for (int i = 0; i < requiredPropsCount; i++) {
          if (!required.get(i)) {
            missingProps.add(requiredPropsNames[i]);
          }
        }
        throw new IllegalStateException(
            "The following props are not marked as optional and were not supplied: "
                + Arrays.toString(missingProps.toArray()));
      }
    }
  }

  public abstract static class ContainerBuilder<T extends ContainerBuilder<T>> extends Builder<T> {

    protected ContainerBuilder(
        ComponentContext c, int defStyleAttr, int defStyleRes, Component component) {
      super(c, defStyleAttr, defStyleRes, component);
    }

    /**
     * The AlignSelf property has the same options and effect as AlignItems but instead of affecting
     * the children within a container, you can apply this property to a single child to change its
     * alignment within its parent. See <a
     * href="https://yogalayout.com/docs/align-content">https://yogalayout.com/docs/align-content</a>
     * for more information.
     *
     * <p>Default: {@link YogaAlign#AUTO}
     */
    public abstract T alignContent(@Nullable YogaAlign alignContent);

    /**
     * The AlignItems property describes how to align children along the cross axis of their
     * container. AlignItems is very similar to JustifyContent but instead of applying to the main
     * axis, it applies to the cross axis. See <a
     * href="https://yogalayout.com/docs/align-items">https://yogalayout.com/docs/align-items</a>
     * for more information.
     *
     * <p>Default: {@link YogaAlign#STRETCH}
     */
    public abstract T alignItems(@Nullable YogaAlign alignItems);

    public abstract T child(@Nullable Component child);

    public abstract T child(@Nullable Component.Builder<?> child);

    /**
     * The JustifyContent property describes how to align children within the main axis of a
     * container. For example, you can use this property to center a child horizontally within a
     * container with FlexDirection = Row or vertically within one with FlexDirection = Column. See
     * <a
     * href="https://yogalayout.com/docs/justify-content">https://yogalayout.com/docs/justify-content</a>
     * for more information.
     *
     * <p>Default: {@link YogaJustify#FLEX_START}
     */
    public abstract T justifyContent(@Nullable YogaJustify justifyContent);

    /** Set this to true if you want the container to be laid out in reverse. */
    public abstract T reverse(boolean reverse);

    /**
     * The FlexWrap property is set on containers and controls what happens when children overflow
     * the size of the container along the main axis. If a container specifies {@link YogaWrap#WRAP}
     * then its children will wrap to the next line instead of overflowing.
     *
     * <p>The next line will have the same FlexDirection as the first line and will appear next to
     * the first line along the cross axis - below it if using FlexDirection = Column and to the
     * right if using FlexDirection = Row. See <a
     * href="https://yogalayout.com/docs/flex-wrap">https://yogalayout.com/docs/flex-wrap</a> for
     * more information.
     *
     * <p>Default: {@link YogaWrap#NO_WRAP}
     */
    public abstract T wrap(@Nullable YogaWrap wrap);
  }

  @Nullable
  public static <T> T getTreePropFromParent(TreeProps parentTreeProps, Class<T> key) {
    return parentTreeProps == null ? null : parentTreeProps.get(key);
  }

  static LinkedList<String> generateHierarchy(String globalKey) {
    LinkedList<String> list = new LinkedList<>();
    String[] keys = globalKey.split(",");

    synchronized (sTypeIdByComponentType) {
      for (String key : keys) {
        String name = ComponentKeyUtils.mapToSimpleName(key, sTypeIdByComponentType);
        list.add(name);
      }
    }

    return list;
  }
}
