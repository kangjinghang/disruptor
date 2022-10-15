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
package com.lmax.disruptor.dsl;

import com.lmax.disruptor.*;

import java.util.*;

/**
 * Provides a repository mechanism to associate {@link EventHandler}s with {@link EventProcessor}s
 *
 * @param <T> the type of the {@link EventHandler}
 */
class ConsumerRepository<T> implements Iterable<ConsumerInfo> // 内部存储着事件处理者（消费者 ConsumerInfo）的信息，相当于事件处理者的仓库
{
    private final Map<EventHandler<?>, EventProcessorInfo<T>> eventProcessorInfoByEventHandler =
        new IdentityHashMap<>(); // 要使用引用做key，所以数据结构使用IdentityHashMap。IdentityHashMap 和HashMap最大的不同，就是使用==而不是equals比较key。
    private final Map<Sequence, ConsumerInfo> eventProcessorInfoBySequence =
        new IdentityHashMap<>(); // 要使用引用做key，所以数据结构使用IdentityHashMap。IdentityHashMap 和HashMap最大的不同，就是使用==而不是equals比较key。
    private final Collection<ConsumerInfo> consumerInfos = new ArrayList<>();
    // 添加事件处理者（Event模式）、事件处理器和序列栅栏到仓库中
    public void add(
        final EventProcessor eventprocessor,
        final EventHandler<? super T> handler,
        final SequenceBarrier barrier)
    {
        final EventProcessorInfo<T> consumerInfo = new EventProcessorInfo<>(eventprocessor, handler, barrier);
        eventProcessorInfoByEventHandler.put(handler, consumerInfo);
        eventProcessorInfoBySequence.put(eventprocessor.getSequence(), consumerInfo);
        consumerInfos.add(consumerInfo); // 添加到仓库中
    }
    // 添加事件处理者（Event模式）到仓库中
    public void add(final EventProcessor processor)
    {
        final EventProcessorInfo<T> consumerInfo = new EventProcessorInfo<>(processor, null, null);
        eventProcessorInfoBySequence.put(processor.getSequence(), consumerInfo);
        consumerInfos.add(consumerInfo);  // 添加到仓库中
    }
    // 添加事件处理者（Work模式）和序列栅栏到仓库中
    public void add(final WorkerPool<T> workerPool, final SequenceBarrier sequenceBarrier)
    {
        final WorkerPoolInfo<T> workerPoolInfo = new WorkerPoolInfo<>(workerPool, sequenceBarrier);
        consumerInfos.add(workerPoolInfo);  // 添加 WorkerPoolInfo 到仓库中
        for (Sequence sequence : workerPool.getWorkerSequences())
        {
            eventProcessorInfoBySequence.put(sequence, workerPoolInfo);
        }
    }
    // 获取当前已经消费到RingBuffer上【事件队列末尾（isEndOfChain）】的事件处理者的序列，可通过参数指定是否要包含已经停止的事件处理者。
    public Sequence[] getLastSequenceInChain(boolean includeStopped)
    {
        List<Sequence> lastSequence = new ArrayList<>();
        for (ConsumerInfo consumerInfo : consumerInfos) // 遍历消费者
        {
            if ((includeStopped || consumerInfo.isRunning()) && consumerInfo.isEndOfChain()) // 正常的话其实都返回
            {
                final Sequence[] sequences = consumerInfo.getSequences(); // 消费者的进度，如果是Work模式就是一个 消费者的进度数组
                Collections.addAll(lastSequence, sequences); // 添加到结果
            }
        }

        return lastSequence.toArray(new Sequence[lastSequence.size()]);
    }

    public EventProcessor getEventProcessorFor(final EventHandler<T> handler)
    {
        final EventProcessorInfo<T> eventprocessorInfo = getEventProcessorInfo(handler);
        if (eventprocessorInfo == null)
        {
            throw new IllegalArgumentException("The event handler " + handler + " is not processing events.");
        }

        return eventprocessorInfo.getEventProcessor();
    }

    public Sequence getSequenceFor(final EventHandler<T> handler)
    {
        return getEventProcessorFor(handler).getSequence();
    }
    // 重置已经处理到【事件队列末尾】的事件处理者的状态
    public void unMarkEventProcessorsAsEndOfChain(final Sequence... barrierEventProcessors)
    {
        for (Sequence barrierEventProcessor : barrierEventProcessors)
        {
            getEventProcessorInfo(barrierEventProcessor).markAsUsedInBarrier();
        }
    }

    @Override
    public Iterator<ConsumerInfo> iterator()
    {
        return consumerInfos.iterator();
    }

    public SequenceBarrier getBarrierFor(final EventHandler<T> handler)
    {
        final ConsumerInfo consumerInfo = getEventProcessorInfo(handler);
        return consumerInfo != null ? consumerInfo.getBarrier() : null;
    }

    private EventProcessorInfo<T> getEventProcessorInfo(final EventHandler<T> handler)
    {
        return eventProcessorInfoByEventHandler.get(handler);
    }

    private ConsumerInfo getEventProcessorInfo(final Sequence barrierEventProcessor)
    {
        return eventProcessorInfoBySequence.get(barrierEventProcessor);
    }
}
