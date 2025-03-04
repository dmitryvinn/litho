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

package com.facebook.litho.widget.collection

import com.facebook.litho.Component

@Suppress("KtDataClass")
data class CollectionChild(
    val id: Any,
    val component: Component? = null,
    val componentFunction: (() -> Component?)? = null,
    val isSticky: Boolean = false,
    val isFullSpan: Boolean = false,
    val spanSize: Int? = null,
    val deps: Array<Any?>? = null,
    val onNearViewport: OnNearViewport? = null,
)
