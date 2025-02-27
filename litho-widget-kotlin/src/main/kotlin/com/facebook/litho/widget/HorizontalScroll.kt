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

import android.view.View
import com.facebook.litho.Component
import com.facebook.litho.Dimen
import com.facebook.litho.ResourcesScope

/** Builder function for creating [HorizontalScrollSpec] components. */
@Suppress("FunctionName")
inline fun ResourcesScope.HorizontalScroll(
    initialScrollPosition: Dimen? = null,
    scrollbarEnabled: Boolean = true,
    fillViewport: Boolean = false,
    eventsController: HorizontalScrollEventsController? = null,
    noinline onScrollChange: ((View, scrollX: Int, oldScrollX: Int) -> Unit)? = null,
    child: ResourcesScope.() -> Component
): HorizontalScroll =
    HorizontalScroll.create(context)
        .contentProps(child())
        .apply { initialScrollPosition?.let { initialScrollPosition(it.toPixels()) } }
        .scrollbarEnabled(scrollbarEnabled)
        .fillViewport(fillViewport)
        .eventsController(eventsController)
        .onScrollChangeListener(onScrollChange)
        .build()
