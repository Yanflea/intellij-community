/*
 * Copyright 2000-2009 JetBrains s.r.o.
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

package com.intellij.util.indexing;

import com.intellij.util.CommonProcessors;
import com.intellij.util.Processor;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This storage is needed for indexing yet unsaved data without saving those changes to 'main' backend storage
 * 
 * @author Eugene Zhuravlev
 *         Date: Dec 10, 2007
 */
public class MemoryIndexStorage<Key, Value> implements IndexStorage<Key, Value> {
  private final Map<Key, ChangeTrackingValueContainer<Value>> myMap = new HashMap<Key,ChangeTrackingValueContainer<Value>>();
  private final IndexStorage<Key, Value> myBackendStorage;
  private final List<BufferingStateListener> myListeners = ContainerUtil.createEmptyCOWList();
  private final AtomicBoolean myBufferingEnabled = new AtomicBoolean(false);
  
  public interface BufferingStateListener {
    void bufferingStateChanged(boolean newState);
    void memoryStorageCleared();
  }
  
  public MemoryIndexStorage(IndexStorage<Key, Value> backend) {
    myBackendStorage = backend;
  }

  public IndexStorage<Key, Value> getBackendStorage() {
    return myBackendStorage;
  }

  public void addBufferingStateListsner(BufferingStateListener listener) {
    myListeners.add(listener);
  }

  public void removeBufferingStateListsner(BufferingStateListener listener) {
    myListeners.remove(listener);
  }
  
  public void setBufferingEnabled(boolean enabled) {
    final boolean wasEnabled = myBufferingEnabled.getAndSet(enabled);
    if (wasEnabled != enabled) {
      for (BufferingStateListener listener : myListeners) {
        listener.bufferingStateChanged(enabled);
      }
    }
  }

  public boolean isBufferingEnabled() {
    return myBufferingEnabled.get();
  }

  public void clearMemoryMap() {
    myMap.clear();
  }

  public void fireMemoryStorageCleared() {
    for (BufferingStateListener listener : myListeners) {
      listener.memoryStorageCleared();
    }
  }

  @Override
  public void close() throws StorageException {
    myBackendStorage.close();
  }

  @Override
  public void clear() throws StorageException {
    clearMemoryMap();
    myBackendStorage.clear();
  }

  @Override
  public void flush() throws IOException {
    myBackendStorage.flush();
  }

  @Override
  public Collection<Key> getKeys() throws StorageException {
    final Set<Key> keys = new HashSet<Key>();
    processKeys(new CommonProcessors.CollectProcessor<Key>(keys));
    return keys;
  }

  @Override
  public boolean processKeys(final Processor<Key> processor) throws StorageException {
    final Set<Key> stopList = new HashSet<Key>();

    Processor<Key> decoratingProcessor = new Processor<Key>() {
      @Override
      public boolean process(final Key key) {
        if (stopList.contains(key)) return true;

        final UpdatableValueContainer<Value> container = myMap.get(key);
        if (container != null && container.size() == 0) {
          return true;
        }
        return processor.process(key);
      }
    };

    for (Key key : myMap.keySet()) {
      if (!decoratingProcessor.process(key)) {
        return false;
      }
      stopList.add(key);
    }
    return myBackendStorage.processKeys(decoratingProcessor);
  }

  @Override
  public void addValue(final Key key, final int inputId, final Value value) throws StorageException {
    if (myBufferingEnabled.get()) {
      getMemValueContainer(key).addValue(inputId, value);
      return;
    }
    final ChangeTrackingValueContainer<Value> valueContainer = myMap.get(key);
    if (valueContainer != null) {
      valueContainer.dropMergedData();
    }

    myBackendStorage.addValue(key, inputId, value);
  }

  @Override
  public void removeValue(final Key key, final int inputId, final Value value) throws StorageException {
    if (myBufferingEnabled.get()) {
      getMemValueContainer(key).removeValue(inputId, value);
      return;
    }
    final ChangeTrackingValueContainer<Value> valueContainer = myMap.get(key);
    if (valueContainer != null) {
      valueContainer.dropMergedData();
    }
    myBackendStorage.removeValue(key, inputId, value);
  }

  @Override
  public void removeAllValues(Key key, int inputId) throws StorageException {
    if (myBufferingEnabled.get()) {
      getMemValueContainer(key).removeAssociatedValue(inputId);
      return;
    }
    final ChangeTrackingValueContainer<Value> valueContainer = myMap.get(key);
    if (valueContainer != null) {
      valueContainer.dropMergedData();
    }

    myBackendStorage.removeAllValues(key, inputId);
  }

  private UpdatableValueContainer<Value> getMemValueContainer(final Key key) {
    ChangeTrackingValueContainer<Value> valueContainer = myMap.get(key);
    if (valueContainer == null) {
      valueContainer = new ChangeTrackingValueContainer<Value>(new ChangeTrackingValueContainer.Initializer<Value>() {
        @Override
        public Object getLock() {
          return this;
        }

        @Override
        public ValueContainer<Value> compute() {
          try {
            return myBackendStorage.read(key);
          }
          catch (StorageException e) {
            throw new RuntimeException(e);
          }
        }
      });
      myMap.put(key, valueContainer);
    }
    return valueContainer;
  }

  @Override
  @NotNull
  public ValueContainer<Value> read(final Key key) throws StorageException {
    final ValueContainer<Value> valueContainer = myMap.get(key);
    if (valueContainer != null) {
      return valueContainer;
    }
    
    return myBackendStorage.read(key);
  }

}
