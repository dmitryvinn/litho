---
id: recycler-collection-component
title: Adding and adapting RecyclerCollection to your App
---

[RecyclerView](https://developer.android.com/reference/android/support/v7/widget/RecyclerView.html) is one of the fundamental building blocks of any Android application that contain a scrolling list of items. Litho recommends using [RecyclerCollectionComponent](pathname:///javadoc/com/facebook/litho/sections/widget/RecyclerCollectionComponent.html) and [Sections](start.mdx) to build scrolling Lists easily.  These APIs can be used to build apps from simple, homogeneous Lists to complex, heterogeneous Lists backed by multiple data sources, all while taking advantage of features such as background layout and incremental mount.

Within this page, you'll cover some of the properties available for the `RecyclerCollectionComponent` that you may need for your use case, such as horizontal Lists, snapping or fixed height.

### Create a RecyclerCollectionComponent

You can use `RecyclerCollectionComponent`, as you would any other component in the framework, by building it and adding it as a child in your layout:

```java
@OnCreateLayout
static Component onCreateLayout(
    final ComponentContext c) {
  return RecyclerCollectionComponent.create(c)
      .section(createSection())
      .build();
}
```

The above code will eventually render as a `RecyclerView` whose rows are backed by the contents of the section.
You can learn more about how to create sections by reading about some of the [building blocks](start.mdx) included in the library.

## Batteries Included

`RecyclerCollectionComponent` includes several practical features for working with Lists. You can see the full list of props it accepts in [the javadocs](pathname:///javadoc/com/facebook/litho/sections/widget/RecyclerCollectionComponent.html). The following sub-sections detail some of its more noteable features.

### Horizontal Lists

`RecyclerCollectionComponent` takes a `RecyclerConfiguration` prop to determine which Layout Manager to use. By default, if this prop is not specified, it uses an implementation of `RecyclerConfiguration` called `ListRecyclerConfiguration`, which will create a LinearLayoutManager with vertical orientation to be used by the `RecyclerCollectionComponent`.

For a horizontal layout, you pass a `ListRecyclerConfiguration` with a horizontal orientation:

```java
final RecyclerCollectionComponentSpec.RecyclerConfiguration
      recyclerConfiguration =
          new ListRecyclerConfiguration(
              LinearLayoutManager.HORIZONTAL, false /* reverse layout */);

final Component component =
    RecyclerCollectionComponent.create(c)
        .section(FooSection.create(new SectionContext(c)).build())
        .recyclerConfiguration(recyclerConfiguration)
        .build();
```

#### Grid Lists

You can also create a Grid List by using [GridRecyclerConfiguration](pathname:///javadoc/com/facebook/litho/sections/widget/GridRecyclerConfiguration.html), as shown in the following code:

```java
GridRecyclerConfiguration.create()
          .orientation(LinearLayoutManager.VERTICAL)
          .numColumns(BOOKMARKS_GRID_NUM_COLUMNS)
          .recyclerBinderConfiguration(RecyclerBinderConfiguration.create().build())
          .build();
```

### Snapping

In horizontally scrollable Lists, the snapping mode for the `RecyclerCollectionComponent` can also be configured through the `ListRecyclerConfiguration`:

```java
final RecyclerCollectionComponentSpec.RecyclerConfiguration
      recyclerConfiguration =
          new ListRecyclerConfiguration(
              LinearLayoutManager.HORIZONTAL, false /* reverse layout */, SNAP_TO_START);

final Component component =
    RecyclerCollectionComponent.create(c)
        .section(FooSection.create(new SectionContext(c)).build())
        .recyclerConfiguration(recyclerConfiguration)
        .build();
```

Other snapping options are `SNAP_NONE`, `SNAP_TO_END`, and `SNAP_TO_CENTER`.

### Setting the Height of a Horizontal RecyclerCollectionComponent

There are three methods to set the height of a horizontally scrolling `RecyclerCollectionComponent`:

* **Fixed height method** - a fixed height is set on the H-Scroll component. This is the most performant method and is recommended where possible.
* **Unknown height method** - the height is not known when the Component is created so let the h-scroll set its height to the height of the first item.
* **Dynamic height method** - lets the h-scroll dynamically change its height to fit the tallest item. This is the least performant method.

For more information on these methods, see the [Nested Scrolling and Measurement](hscrolls.mdx) page of the Litho documentation.

### Pull to Refresh

`RecyclerCollectionComponent` enables pull-to-refresh by default and sends an event handler to the underlying `Recycler` that will trigger a refresh on the SectionTree.

To disable this functionality, you need to set the `disablePTR` prop to true:

```java
final Component component =
    RecyclerCollectionComponent.create(c)
        .section(FooSection.create(new SectionContext(c)).build())
        .recyclerConfiguration(recyclerConfiguration)
        .disablePTR(true)
        .build();
 ```

### Loading, Empty, and Error screens

With the sections API, you can integrate data fetching through [loading events](../communicating-with-the-ui.md#loadingstate-loadingstate) and [services](services.md).  `RecyclerCollectionComponent` can listen to these [loading events](pathname:///javadoc/com/facebook/litho/sections/LoadingEvent.html) and respond accordingly.  Through the props `loadingComponent`, `emptyComponent`, and `errorComponent`, you can specify what to show when certain things occur when fetching data:

 - `loadingComponent` - data is being loaded and there's nothing in the list
 - `emptyComponent` - data has finished loading and there's nothing to show.
 - `errorComponent` - data loading has failed and there's nothing in the list.

```java
final Component component =
    RecyclerCollectionComponent.create(c)
        .section(FooSection.create(new SectionContext(c)).build())
        .recyclerConfiguration(recyclerConfiguration)
        .loadingComponent(
            Progress.create(c)
                .build())
        .errorComponent(
            Text.create(c)
                .text("Data Fetch has failed").build())
        .emptyComponent(
            Text.create(c)
                .text("No data to show").build())
        .build();
 ```

For details of props supported by `RecyclerCollectionComponent`, see it's [Javadoc web page](pathname:///javadoc/com/facebook/litho/sections/widget/RecyclerCollectionComponent.html).
