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

package com.facebook.samples.litho.onboarding.model

import androidx.annotation.DrawableRes
import com.facebook.samples.litho.R

// start_example
class User(val username: String, @DrawableRes val avatarRes: Int)

class Post(val user: User, @DrawableRes val imageRes: Int)
// end_example

val OBI_WAN_POST =
    Post(
        user = User(username = "Obi-Wan Kenobi", avatarRes = R.drawable.ic_launcher),
        imageRes = R.drawable.header)
