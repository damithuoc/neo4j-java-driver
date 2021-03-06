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
package org.neo4j.driver.internal.async;

import io.netty.channel.Channel;
import io.netty.channel.pool.ChannelPool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import org.neo4j.driver.internal.BoltServerAddress;
import org.neo4j.driver.internal.async.inbound.InboundMessageDispatcher;
import org.neo4j.driver.internal.handlers.ChannelReleasingResetResponseHandler;
import org.neo4j.driver.internal.handlers.ResetResponseHandler;
import org.neo4j.driver.internal.messaging.Message;
import org.neo4j.driver.internal.messaging.PullAllMessage;
import org.neo4j.driver.internal.messaging.ResetMessage;
import org.neo4j.driver.internal.messaging.RunMessage;
import org.neo4j.driver.internal.metrics.ListenerEvent;
import org.neo4j.driver.internal.metrics.MetricsListener;
import org.neo4j.driver.internal.spi.Connection;
import org.neo4j.driver.internal.spi.ResponseHandler;
import org.neo4j.driver.internal.util.Clock;
import org.neo4j.driver.internal.util.ServerVersion;
import org.neo4j.driver.v1.Value;

import static java.util.Collections.emptyMap;
import static org.neo4j.driver.internal.async.ChannelAttributes.setTerminationReason;

public class NettyConnection implements Connection
{
    private final Channel channel;
    private final InboundMessageDispatcher messageDispatcher;
    private final BoltServerAddress serverAddress;
    private final ServerVersion serverVersion;
    private final ChannelPool channelPool;
    private final CompletableFuture<Void> releaseFuture;
    private final Clock clock;

    private final AtomicReference<Status> status = new AtomicReference<>( Status.OPEN );
    private final MetricsListener metricsListener;
    private final ListenerEvent inUseEvent;

    public NettyConnection( Channel channel, ChannelPool channelPool, Clock clock, MetricsListener metricsListener )
    {
        this.channel = channel;
        this.messageDispatcher = ChannelAttributes.messageDispatcher( channel );
        this.serverAddress = ChannelAttributes.serverAddress( channel );
        this.serverVersion = ChannelAttributes.serverVersion( channel );
        this.channelPool = channelPool;
        this.releaseFuture = new CompletableFuture<>();
        this.clock = clock;
        this.metricsListener = metricsListener;
        this.inUseEvent = metricsListener.createListenerEvent();
        metricsListener.afterConnectionCreated( this.serverAddress, this.inUseEvent );
    }

    @Override
    public boolean isOpen()
    {
        return status.get() == Status.OPEN;
    }

    @Override
    public void enableAutoRead()
    {
        if ( isOpen() )
        {
            setAutoRead( true );
        }
    }

    @Override
    public void disableAutoRead()
    {
        if ( isOpen() )
        {
            setAutoRead( false );
        }
    }

    @Override
    public void run( String statement, Map<String,Value> parameters, ResponseHandler runHandler,
            ResponseHandler pullAllHandler )
    {
        if ( verifyOpen( runHandler, pullAllHandler ) )
        {
            run( statement, parameters, runHandler, pullAllHandler, false );
        }
    }

    @Override
    public void runAndFlush( String statement, Map<String,Value> parameters, ResponseHandler runHandler,
            ResponseHandler pullAllHandler )
    {
        if ( verifyOpen( runHandler, pullAllHandler ) )
        {
            run( statement, parameters, runHandler, pullAllHandler, true );
        }
    }

    @Override
    public CompletionStage<Void> reset()
    {
        CompletableFuture<Void> result = new CompletableFuture<>();
        ResetResponseHandler handler = new ResetResponseHandler( messageDispatcher, result );
        writeResetMessageIfNeeded( handler, true );
        return result;
    }

    @Override
    public CompletionStage<Void> release()
    {
        if ( status.compareAndSet( Status.OPEN, Status.RELEASED ) )
        {
            ChannelReleasingResetResponseHandler handler = new ChannelReleasingResetResponseHandler( channel,
                    channelPool, messageDispatcher, clock, releaseFuture );

            writeResetMessageIfNeeded( handler, false );
            metricsListener.afterConnectionReleased( this.serverAddress, this.inUseEvent );
        }
        return releaseFuture;
    }

    @Override
    public void terminateAndRelease( String reason )
    {
        if ( status.compareAndSet( Status.OPEN, Status.TERMINATED ) )
        {
            setTerminationReason( channel, reason );
            channel.close();
            channelPool.release( channel );
            releaseFuture.complete( null );
            metricsListener.afterConnectionReleased( this.serverAddress, this.inUseEvent );
        }
    }

    @Override
    public BoltServerAddress serverAddress()
    {
        return serverAddress;
    }

    @Override
    public ServerVersion serverVersion()
    {
        return serverVersion;
    }

    private void run( String statement, Map<String,Value> parameters, ResponseHandler runHandler,
            ResponseHandler pullAllHandler, boolean flush )
    {
        writeMessagesInEventLoop( new RunMessage( statement, parameters ), runHandler, PullAllMessage.PULL_ALL,
                pullAllHandler, flush );
    }

    private void writeResetMessageIfNeeded( ResponseHandler resetHandler, boolean isSessionReset )
    {
        channel.eventLoop().execute( () ->
        {
            if ( isSessionReset && !isOpen() )
            {
                resetHandler.onSuccess( emptyMap() );
            }
            else
            {
                messageDispatcher.muteAckFailure();
                // auto-read could've been disabled, re-enable it to automatically receive response for RESET
                setAutoRead( true );
                writeAndFlushMessage( ResetMessage.RESET, resetHandler );
            }
        } );
    }

    private void writeMessagesInEventLoop( Message message1, ResponseHandler handler1, Message message2,
            ResponseHandler handler2, boolean flush )
    {
        channel.eventLoop().execute( () -> writeMessages( message1, handler1, message2, handler2, flush ) );
    }

    private void writeMessages( Message message1, ResponseHandler handler1, Message message2, ResponseHandler handler2,
            boolean flush )
    {
        messageDispatcher.queue( handler1 );
        messageDispatcher.queue( handler2 );

        channel.write( message1, channel.voidPromise() );

        if ( flush )
        {
            channel.writeAndFlush( message2, channel.voidPromise() );
        }
        else
        {
            channel.write( message2, channel.voidPromise() );
        }
    }

    private void writeAndFlushMessage( Message message, ResponseHandler handler )
    {
        messageDispatcher.queue( handler );
        channel.writeAndFlush( message, channel.voidPromise() );
    }

    private void setAutoRead( boolean value )
    {
        channel.config().setAutoRead( value );
    }

    private boolean verifyOpen( ResponseHandler runHandler, ResponseHandler pullAllHandler )
    {
        Status connectionStatus = this.status.get();
        switch ( connectionStatus )
        {
        case OPEN:
            return true;
        case RELEASED:
            Exception error = new IllegalStateException( "Connection has been released to the pool and can't be used" );
            runHandler.onFailure( error );
            pullAllHandler.onFailure( error );
            return false;
        case TERMINATED:
            Exception terminatedError = new IllegalStateException( "Connection has been terminated and can't be used" );
            runHandler.onFailure( terminatedError );
            pullAllHandler.onFailure( terminatedError );
            return false;
        default:
            throw new IllegalStateException( "Unknown status: " + connectionStatus );
        }
    }

    private enum Status
    {
        OPEN,
        RELEASED,
        TERMINATED
    }
}
