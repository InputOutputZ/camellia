package com.netease.nim.camellia.hot.key.server.calculate;

import com.netease.nim.camellia.hot.key.common.model.KeyCounter;
import com.netease.nim.camellia.hot.key.server.conf.WorkQueueType;
import com.netease.nim.camellia.tools.executor.CamelliaThreadFactory;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Created by caojiajun on 2023/5/9
 */
public class HotKeyCalculatorQueue {

    private static final Logger logger = LoggerFactory.getLogger(HotKeyCalculatorQueue.class);

    private static final LongAdder fail = new LongAdder();
    static {
        Executors.newSingleThreadScheduledExecutor(new CamelliaThreadFactory("hot-key-calculator-queue-full-log"))
                .scheduleAtFixedRate(() -> {
                    long count = fail.sumThenReset();
                    if (count > 0) {
                        logger.error("HotKeyCalculatorQueue full, count = {}", count);
                    }
                }, 5, 5, TimeUnit.SECONDS);
    }

    private static final AtomicLong idGen = new AtomicLong(0);
    private final BlockingQueue<KeyCounter> queue;
    private final long id;
    private final LongAdder pendingSize = new LongAdder();
    private final LongAdder discardCount = new LongAdder();

    public HotKeyCalculatorQueue(WorkQueueType workQueueType, int bizWorkQueueCapacity) {
        this.queue = initQueue(workQueueType, bizWorkQueueCapacity);
        this.id = idGen.getAndIncrement();
    }

    private BlockingQueue<KeyCounter> initQueue(WorkQueueType workQueueType, int bizWorkQueueCapacity) {
        if (workQueueType == WorkQueueType.LinkedBlockingQueue) {
            return new LinkedBlockingQueue<>(bizWorkQueueCapacity);
        } else if (workQueueType == WorkQueueType.ArrayBlockingQueue) {
            return new ArrayBlockingQueue<>(bizWorkQueueCapacity);
        } else if (workQueueType == WorkQueueType.MpscBlockingConsumerArrayQueue) {
            return new MpscBlockingConsumerArrayQueue<>(bizWorkQueueCapacity);
        } else {
            return new LinkedBlockingQueue<>(bizWorkQueueCapacity);
        }
    }

    public long getId() {
        return id;
    }

    public void push(List<KeyCounter> counters) {
        pendingSize.add(counters.size());
        for (KeyCounter counter : counters) {
            boolean success = queue.offer(counter);
            if (!success) {
                fail.increment();
                pendingSize.decrement();
                discardCount.increment();
            }
        }
    }

    public long discardCount() {
        return discardCount.sumThenReset();
    }

    public long pendingSize() {
        return pendingSize.sum();
    }

    public void start(HotKeyCalculator calculator) {
        new Thread(() -> {
            while (true) {
                try {
                    KeyCounter counter = queue.take();
                    pendingSize.decrement();
                    calculator.calculate(counter);
                } catch (Exception e) {
                    logger.error("hot key calculate error", e);
                }
            }
        }, "hot-key-calculator-queue-" + id).start();
        logger.info("hot key calculator {} start success", id);
    }
}
