/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.consistency.checking.full;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.neo4j.io.pagecache.tracing.DefaultPageCacheTracer;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.io.pagecache.tracing.cursor.PageCursorTracer;
import org.neo4j.test.Race;

import static java.lang.System.currentTimeMillis;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.concurrent.locks.LockSupport.parkNanos;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RecordCheckWorkerTest
{
    private ExecutorService executorService;

    @BeforeEach
    void setUp()
    {
        executorService = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown()
    {
        executorService.shutdownNow();
    }

    @Test
    void shouldDoProcessingInitializationInOrder() throws Throwable
    {
        // GIVEN
        final Race race = new Race();
        final AtomicInteger coordination = new AtomicInteger( -1 );
        final AtomicInteger expected = new AtomicInteger();
        final int threads = 30;
        @SuppressWarnings( "unchecked" )
        final RecordCheckWorker<Integer>[] workers = new RecordCheckWorker[threads];
        final RecordProcessor<Integer> processor = new RecordProcessor.Adapter<>()
        {
            @Override
            public void process( Integer record, PageCursorTracer cursorTracer )
            {
                // We're testing init() here, not really process()
            }

            @Override
            public void init( int id )
            {
                assertEquals( id, expected.getAndAdd( 1 ) );
            }
        };
        for ( int id = 0; id < threads; id++ )
        {
            ArrayBlockingQueue<Integer> queue = new ArrayBlockingQueue<>( 10 );
            race.addContestant( workers[id] = new RecordCheckWorker<>( id, coordination, queue, processor, PageCacheTracer.NULL ) );
        }
        race.addContestant( () ->
        {
            try
            {
                long end = currentTimeMillis() + SECONDS.toMillis( 100 );
                while ( currentTimeMillis() < end && expected.get() < threads )
                {
                    parkNanos( MILLISECONDS.toNanos( 10 ) );
                }
                assertEquals( threads, expected.get() );
            }
            finally
            {
                for ( RecordCheckWorker<Integer> worker : workers )
                {
                    worker.done();
                }
            }
        } );

        // WHEN
        race.go();
    }

    @Test
    void propagateCursorTracerToProcessor() throws InterruptedException, ExecutionException
    {
        CountDownLatch processDoneLatch = new CountDownLatch( 1 );
        var processor = new RecordProcessor.Adapter<Integer>()
        {
            @Override
            public void process( Integer o, PageCursorTracer cursorTracer )
            {
                cursorTracer.beginPin( false, 1, null ).done();
                processDoneLatch.countDown();
            }
        };
        final AtomicInteger coordination = new AtomicInteger( -1 );
        ArrayBlockingQueue<Integer> queue = new ArrayBlockingQueue<>( 10 );
        queue.add( 1 );
        var pageCacheTracer = new DefaultPageCacheTracer();

        final RecordCheckWorker<Integer> worker = new RecordCheckWorker<>( 0, coordination, queue, processor, pageCacheTracer );
        var workerFuture = executorService.submit( worker );

        processDoneLatch.await();
        worker.done();
        workerFuture.get();

        assertThat( pageCacheTracer.pins() ).isEqualTo( 1 );
    }
}
