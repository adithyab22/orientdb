/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
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
package com.orientechnologies.orient.core.index.sbtree.local;

import com.orientechnologies.common.directmemory.ODirectMemoryPointer;
import com.orientechnologies.common.serialization.types.OBinarySerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.orient.core.storage.impl.local.paginated.base.ODurablePage;

import java.io.IOException;

/**
 * @author Andrey Lomakin <a href="mailto:lomakin.andrey@gmail.com">Andrey Lomakin</a>
 * @since 4/15/14
 */
public class ONullBucket<V> extends ODurablePage {
  private final OBinarySerializer<V> valueSerializer;

  public ONullBucket(ODirectMemoryPointer pagePointer, TrackMode trackMode, OBinarySerializer<V> valueSerializer) {
    super(pagePointer, trackMode);
    this.valueSerializer = valueSerializer;

    setByteValue(0, (byte) 0);
  }

  public void setEntry(OSBTreeValue<V> value) throws IOException {
    setByteValue(0, (byte) 1);

    if (value.isLink()) {
      setByteValue(1, (byte) 0);
      setLongValue(2, value.getLink());
    } else {
      final int valueSize = valueSerializer.getObjectSize(value.getValue());

      final byte[] serializedValue = new byte[valueSize];
      valueSerializer.serializeNative(value.getValue(), serializedValue, 0);

      setByteValue(1, (byte) 1);
      setBinaryValue(2, serializedValue);
    }
  }

  public OSBTreeValue<V> getValue() {
    if (getByteValue(0) == 0)
      return null;

    final boolean isLink = getByteValue(1) == 0;
    if (isLink)
      return new OSBTreeValue<V>(true, getLongValue(2), null);

    return new OSBTreeValue<V>(false, -1, valueSerializer.deserializeFromDirectMemory(pagePointer, 2));
  }

  public void removeValue() {
    setByteValue(0, (byte) 0);
  }
}
