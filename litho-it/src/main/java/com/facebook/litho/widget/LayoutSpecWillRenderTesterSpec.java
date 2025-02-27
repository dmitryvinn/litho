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

package com.facebook.litho.widget;

import com.facebook.litho.Column;
import com.facebook.litho.Component;
import com.facebook.litho.ComponentContext;
import com.facebook.litho.LifecycleStep;
import com.facebook.litho.Row;
import com.facebook.litho.Wrapper;
import com.facebook.litho.annotations.LayoutSpec;
import com.facebook.litho.annotations.OnCreateLayout;
import com.facebook.litho.annotations.Prop;
import com.facebook.litho.widget.events.EventWithoutAnnotation;
import java.util.List;

@LayoutSpec(events = EventWithoutAnnotation.class)
public class LayoutSpecWillRenderTesterSpec {

  @OnCreateLayout
  static Component onCreateLayout(ComponentContext c, @Prop List<LifecycleStep.StepInfo> steps) {
    Component component = LayoutSpecLifecycleTester.create(c).steps(steps).build();
    Component.willRender(c, component);

    return Row.create(c)
        .child(Column.create(c).child(Wrapper.create(c).delegate(component)))
        .build();
  }
}
