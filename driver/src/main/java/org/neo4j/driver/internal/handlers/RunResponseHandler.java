/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
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
package org.neo4j.driver.internal.handlers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.neo4j.driver.internal.spi.ResponseHandler;
import org.neo4j.driver.v1.Value;

import static java.util.Collections.emptyList;
import static org.neo4j.driver.internal.util.MetadataUtil.extractResultAvailableAfter;
import static org.neo4j.driver.internal.util.MetadataUtil.extractStatementKeys;

public class RunResponseHandler implements ResponseHandler
{
    private final CompletableFuture<Void> runCompletedFuture;

    private List<String> statementKeys = emptyList();
    private long resultAvailableAfter = -1;

    public RunResponseHandler( CompletableFuture<Void> runCompletedFuture )
    {
        this.runCompletedFuture = runCompletedFuture;
    }

    @Override
    public void onSuccess( Map<String,Value> metadata )
    {
        statementKeys = extractStatementKeys( metadata );
        resultAvailableAfter = extractResultAvailableAfter( metadata );

        completeRunFuture();
    }

    @Override
    public void onFailure( Throwable error )
    {
        completeRunFuture();
    }

    @Override
    public void onRecord( Value[] fields )
    {
        throw new UnsupportedOperationException();
    }

    public List<String> statementKeys()
    {
        return statementKeys;
    }

    public long resultAvailableAfter()
    {
        return resultAvailableAfter;
    }

    /**
     * Complete the given future with {@code null}. Future is never completed exceptionally because callers are only
     * interested in when RUN completes and not how. Async API needs to wait for RUN because it needs to access
     * statement keys.
     */
    private void completeRunFuture()
    {
        runCompletedFuture.complete( null );
    }
}
