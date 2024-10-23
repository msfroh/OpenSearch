/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index;

import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.index.engine.Engine;

import java.util.List;

public interface IngestionSource<T extends IngestionSource.SourcePointer> {
    interface SourcePointer {
        byte[] serialize();
    }

    class ReadResult<T> {
        T nextPointer;
        Engine.Operation indexOperation;
    }

    T createSourcePointer(int shardNum);

    List<ReadResult<T>> readNext(T pointer, int maxOperations);

    T deserialize(byte[] serializedPointer);

}
