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
package org.neo4j.driver.internal;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.function.Consumer;

import org.neo4j.driver.internal.spi.Connection;
import org.neo4j.driver.internal.spi.ResponseHandler;
import org.neo4j.driver.v1.Transaction;
import org.neo4j.driver.v1.exceptions.ClientException;

import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.neo4j.driver.v1.util.TestUtil.await;
import static org.neo4j.driver.v1.util.TestUtil.connectionMock;

class ExplicitTransactionTest
{
    @Test
    void shouldRollbackOnImplicitFailure()
    {
        // Given
        Connection connection = connectionMock();
        ExplicitTransaction tx = beginTx( connection );

        // When
        tx.close();

        // Then
        InOrder order = inOrder( connection );
        order.verify( connection ).run( eq( "BEGIN" ), any(), any(), any() );
        order.verify( connection ).runAndFlush( eq( "ROLLBACK" ), any(), any(), any() );
        order.verify( connection ).release();
    }

    @Test
    void shouldRollbackOnExplicitFailure()
    {
        // Given
        Connection connection = connectionMock();
        ExplicitTransaction tx = beginTx( connection );

        // When
        tx.failure();
        tx.success(); // even if success is called after the failure call!
        tx.close();

        // Then
        InOrder order = inOrder( connection );
        order.verify( connection ).run( eq( "BEGIN" ), any(), any(), any() );
        order.verify( connection ).runAndFlush( eq( "ROLLBACK" ), any(), any(), any() );
        order.verify( connection ).release();
    }

    @Test
    void shouldCommitOnSuccess()
    {
        // Given
        Connection connection = connectionMock();
        ExplicitTransaction tx = beginTx( connection );

        // When
        tx.success();
        tx.close();

        // Then
        InOrder order = inOrder( connection );
        order.verify( connection ).run( eq( "BEGIN" ), any(), any(), any() );
        order.verify( connection ).runAndFlush( eq( "COMMIT" ), any(), any(), any() );
        order.verify( connection ).release();
    }

    @Test
    void shouldOnlyQueueMessagesWhenNoBookmarkGiven()
    {
        Connection connection = connectionMock();

        beginTx( connection, Bookmark.empty() );

        verify( connection ).run( eq( "BEGIN" ), any(), any(), any() );
        verify( connection, never() ).runAndFlush( any(), any(), any(), any() );
    }

    @Test
    void shouldFlushWhenBookmarkGiven()
    {
        Bookmark bookmark = Bookmark.from( "hi, I'm bookmark" );
        Connection connection = connectionMock();

        beginTx( connection, bookmark );

        verify( connection ).runAndFlush( eq( "BEGIN" ), any(), any(), any() );
        verify( connection, never() ).run( any(), any(), any(), any() );
    }

    @Test
    void shouldBeOpenAfterConstruction()
    {
        Transaction tx = beginTx( connectionMock() );

        assertTrue( tx.isOpen() );
    }

    @Test
    void shouldBeOpenWhenMarkedForSuccess()
    {
        Transaction tx = beginTx( connectionMock() );

        tx.success();

        assertTrue( tx.isOpen() );
    }

    @Test
    void shouldBeOpenWhenMarkedForFailure()
    {
        Transaction tx = beginTx( connectionMock() );

        tx.failure();

        assertTrue( tx.isOpen() );
    }

    @Test
    void shouldBeClosedWhenMarkedAsTerminated()
    {
        ExplicitTransaction tx = beginTx( connectionMock() );

        tx.markTerminated();

        assertTrue( tx.isOpen() );
    }

    @Test
    void shouldBeClosedAfterCommit()
    {
        Transaction tx = beginTx( connectionMock() );

        tx.success();
        tx.close();

        assertFalse( tx.isOpen() );
    }

    @Test
    void shouldBeClosedAfterRollback()
    {
        Transaction tx = beginTx( connectionMock() );

        tx.failure();
        tx.close();

        assertFalse( tx.isOpen() );
    }

    @Test
    void shouldBeClosedWhenMarkedTerminatedAndClosed()
    {
        ExplicitTransaction tx = beginTx( connectionMock() );

        tx.markTerminated();
        tx.close();

        assertFalse( tx.isOpen() );
    }

    @Test
    void shouldHaveEmptyBookmarkInitially()
    {
        ExplicitTransaction tx = beginTx( connectionMock() );
        assertTrue( tx.bookmark().isEmpty() );
    }

    @Test
    void shouldNotKeepInitialBookmark()
    {
        ExplicitTransaction tx = beginTx( connectionMock(), Bookmark.from( "Dog" ) );
        assertTrue( tx.bookmark().isEmpty() );
    }

    @Test
    void shouldNotOverwriteBookmarkWithNull()
    {
        ExplicitTransaction tx = beginTx( connectionMock() );
        tx.setBookmark( Bookmark.from( "Cat" ) );
        assertEquals( "Cat", tx.bookmark().maxBookmarkAsString() );
        tx.setBookmark( null );
        assertEquals( "Cat", tx.bookmark().maxBookmarkAsString() );
    }

    @Test
    void shouldNotOverwriteBookmarkWithEmptyBookmark()
    {
        ExplicitTransaction tx = beginTx( connectionMock() );
        tx.setBookmark( Bookmark.from( "Cat" ) );
        assertEquals( "Cat", tx.bookmark().maxBookmarkAsString() );
        tx.setBookmark( Bookmark.empty() );
        assertEquals( "Cat", tx.bookmark().maxBookmarkAsString() );
    }

    @Test
    void shouldReleaseConnectionWhenBeginFails()
    {
        RuntimeException error = new RuntimeException( "Wrong bookmark!" );
        Connection connection = connectionWithBegin( handler -> handler.onFailure( error ) );
        ExplicitTransaction tx = new ExplicitTransaction( connection, mock( NetworkSession.class ) );

        RuntimeException e = assertThrows( RuntimeException.class, () -> await( tx.beginAsync( Bookmark.from( "SomeBookmark" ) ) ) );
        assertEquals( error, e );

        verify( connection ).release();
    }

    @Test
    void shouldNotReleaseConnectionWhenBeginSucceeds()
    {
        Connection connection = connectionWithBegin( handler -> handler.onSuccess( emptyMap() ) );
        ExplicitTransaction tx = new ExplicitTransaction( connection, mock( NetworkSession.class ) );
        await( tx.beginAsync( Bookmark.from( "SomeBookmark" ) ) );

        verify( connection, never() ).release();
    }

    @Test
    void shouldReleaseConnectionWhenTerminatedAndCommitted()
    {
        Connection connection = connectionMock();
        ExplicitTransaction tx = new ExplicitTransaction( connection, mock( NetworkSession.class ) );

        tx.markTerminated();

        assertThrows( ClientException.class, () -> await( tx.commitAsync() ) );

        assertFalse( tx.isOpen() );
        verify( connection ).release();
    }

    @Test
    void shouldReleaseConnectionWhenTerminatedAndRolledBack()
    {
        Connection connection = connectionMock();
        ExplicitTransaction tx = new ExplicitTransaction( connection, mock( NetworkSession.class ) );

        tx.markTerminated();
        await( tx.rollbackAsync() );

        verify( connection ).release();
    }

    private static ExplicitTransaction beginTx( Connection connection )
    {
        return beginTx( connection, Bookmark.empty() );
    }

    private static ExplicitTransaction beginTx( Connection connection, Bookmark initialBookmark )
    {
        return beginTx( connection, mock( NetworkSession.class ), initialBookmark );
    }

    private static ExplicitTransaction beginTx( Connection connection, NetworkSession session,
            Bookmark initialBookmark )
    {
        ExplicitTransaction tx = new ExplicitTransaction( connection, session );
        return await( tx.beginAsync( initialBookmark ) );
    }

    private static Connection connectionWithBegin( Consumer<ResponseHandler> beginBehaviour )
    {
        Connection connection = mock( Connection.class );

        doAnswer( invocation ->
        {
            ResponseHandler beginHandler = invocation.getArgument( 3 );
            beginBehaviour.accept( beginHandler );
            return null;
        } ).when( connection ).runAndFlush( eq( "BEGIN" ), any(), any(), any() );

        return connection;
    }
}
