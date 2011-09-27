/*
 * Copyright 2000-2011 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.navigation;

import com.intellij.openapi.util.ClassExtension;
import org.jetbrains.annotations.NotNull;

/**
 * @author yole
 */
public class ItemPresentationProviders extends ClassExtension<ItemPresentationProvider> {
  public static final ItemPresentationProviders INSTANCE = new ItemPresentationProviders();

  private ItemPresentationProviders() {
    super("com.intellij.itemPresentationProvider");
  }

  public static <T extends NavigationItem> ItemPresentationProvider<T> getItemPresentationProvider(@NotNull T element) {
    return INSTANCE.forClass(element.getClass());
  }

  public static ItemPresentation getItemPresentation(NavigationItem element) {
    return getItemPresentationProvider(element).getPresentation(element);
  }
}