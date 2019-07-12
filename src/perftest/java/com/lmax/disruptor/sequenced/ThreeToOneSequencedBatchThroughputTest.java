/*
 * Copyright 2011 LMAX Ltd.
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
package com.lmax.disruptor.sequenced;

import static com.lmax.disruptor.RingBuffer.createMultiProducer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.lmax.disruptor.AbstractPerfTestDisruptor;
import com.lmax.disruptor.BatchEventProcessor;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.support.ValueAdditionEventHandler;
import com.lmax.disruptor.support.ValueBatchPublisher;
import com.lmax.disruptor.support.ValueEvent;
import com.lmax.disruptor.util.DaemonThreadFactory;

/**
 * <pre>
 *
 * Sequence a series of events from multiple publishers going to one event processor.
 *
 * +----+
 * | P1 |------+
 * +----+      |
 *             v
 * +----+    +-----+
 * | P1 |--->| EP1 |
 * +----+    +-----+
 *             ^
 * +----+      |
 * | P3 |------+
 * +----+
 *
 * Disruptor:
 * ==========
 *             track to prevent wrap
 *             +--------------------+
 *             |                    |
 *             |                    v
 * +----+    +====+    +====+    +-----+
 * | P1 |--->| RB |<---| SB |    | EP1 |
 * +----+    +====+    +====+    +-----+
 *             ^   get    ^         |
 * +----+      |          |         |
 * | P2 |------+          +---------+
 * +----+      |            waitFor
 *             |
 * +----+      |
 * | P3 |------+
 * +----+
 *
 * P1  - Publisher 1
 * P2  - Publisher 2
 * P3  - Publisher 3
 * RB  - RingBuffer
 * SB  - SequenceBarrier
 * EP1 - EventProcessor 1
 *
 * </pre>
 *
 * @author mikeb01
 */
public final class ThreeToOneSequencedBatchThroughputTest extends AbstractPerfTestDisruptor {
    // Number of writers
    private static int NUM_PUBLISHERS = 3;
    // Size of the buffer for message passing
    private static int BUFFER_SIZE = 1024 * 64;
    // Total number of messages received by the reader. Each writer sends 1/n of the messages
    private static long ITERATIONS = 1000L * 1000L * 100L;
    private ExecutorService executor = null;
    private CyclicBarrier cyclicBarrier = null;

    private RingBuffer<ValueEvent> ringBuffer = null;
    private SequenceBarrier sequenceBarrier = null;
    private ValueAdditionEventHandler handler = null;
    private BatchEventProcessor<ValueEvent> batchEventProcessor = null;
    private ValueBatchPublisher[] valuePublishers = null;

    private void initialize() {
        executor = Executors.newFixedThreadPool(NUM_PUBLISHERS + 1, DaemonThreadFactory.INSTANCE);
        cyclicBarrier = new CyclicBarrier(NUM_PUBLISHERS + 1);

        ringBuffer = createMultiProducer(ValueEvent.EVENT_FACTORY, BUFFER_SIZE, new BusySpinWaitStrategy());

        sequenceBarrier = ringBuffer.newBarrier();
        handler = new ValueAdditionEventHandler();
        batchEventProcessor = new BatchEventProcessor<ValueEvent>(ringBuffer, sequenceBarrier, handler);
        valuePublishers = new ValueBatchPublisher[NUM_PUBLISHERS];
        {
            for (int i = 0; i < NUM_PUBLISHERS; i++) {
                valuePublishers[i] = new ValueBatchPublisher(cyclicBarrier, ringBuffer, ITERATIONS / NUM_PUBLISHERS, 10);
            }

            ringBuffer.addGatingSequences(batchEventProcessor.getSequence());
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected int getRequiredProcessorCount() {
        return NUM_PUBLISHERS + 1;
    }

    @Override
    protected long runDisruptorPass() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        handler.reset(latch, batchEventProcessor.getSequence().get() + ((ITERATIONS / NUM_PUBLISHERS) * NUM_PUBLISHERS));

        Future<?>[] futures = new Future[NUM_PUBLISHERS];
        for (int i = 0; i < NUM_PUBLISHERS; i++) {
            futures[i] = executor.submit(valuePublishers[i]);
        }
        executor.submit(batchEventProcessor);

        long start = System.currentTimeMillis();
        cyclicBarrier.await();

        for (int i = 0; i < NUM_PUBLISHERS; i++) {
            futures[i].get();
        }

        latch.await();

        long duration = System.currentTimeMillis() - start;
        long opsPerSecond = (ITERATIONS * 1000L) / duration;
        batchEventProcessor.halt();

        System.out.format("Duration: %d\n", duration);

        return opsPerSecond;
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < args.length; ) {
            switch (args[i]) {
                case "--num_writers": {
                    NUM_PUBLISHERS = Integer.parseInt(args[i + 1]);
                    i += 2;
                    break;
                }
                case "--num_messages": {
                    ITERATIONS = Integer.parseInt(args[i + 1]);
                    i += 2;
                    break;
                }
                case "--queue_length": {
                    BUFFER_SIZE = Integer.parseInt(args[i + 1]);
                    BUFFER_SIZE = nextPowerOf2(BUFFER_SIZE);
                    i += 2;
                    break;
                }
                default: {
                    throw new RuntimeException("Unknown arguments: " + args[i]);
                }
            }
        }
        System.out.format("W: %d, N: %d, L: %d\n", NUM_PUBLISHERS, ITERATIONS, BUFFER_SIZE);
        ThreeToOneSequencedBatchThroughputTest test = new ThreeToOneSequencedBatchThroughputTest();
        test.initialize();
        test.testImplementations();
    }

    public static int nextPowerOf2(int x) {
        if (x > 0) {
            x--;
            for (int i = x >> 1; i != 0; i >>= 1) {
                x |= i;
            }
            
        }
        return x + 1;
    }
}
