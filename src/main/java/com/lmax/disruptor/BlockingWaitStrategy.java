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
package com.lmax.disruptor;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.lmax.disruptor.util.ThreadHints;

/**
 * Blocking strategy that uses a lock and condition variable for {@link EventProcessor}s waiting on a barrier.
 * <p>
 * This strategy can be used when throughput and low-latency are not as important as CPU resource.
 */
public final class BlockingWaitStrategy implements WaitStrategy
{
    private final Lock lock = new ReentrantLock();
    private final Condition processorNotifyCondition = lock.newCondition();

    @Override
    public long waitFor(long sequence, Sequence cursorSequence, Sequence dependentSequence, SequenceBarrier barrier)
        throws AlertException, InterruptedException
    {
        long availableSequence;
        if (cursorSequence.get() < sequence) // 如果RingBuffer上当前可用的序列值小于要申请的序列值
        {
            lock.lock();
            try
            {
                while (cursorSequence.get() < sequence) // 再次检测 double check。 当给定的序号大于生产者游标序号时，进行等待
                {
                    barrier.checkAlert(); // 检查序列栅栏状态(事件处理器是否被关闭)。要检查alert状态。如果不检查将导致不能关闭Disruptor（一直在while true 循环中）
                    processorNotifyCondition.await(); // 当前线程在processorNotifyCondition条件上等待。在Sequencer中publish进行唤醒；等待消费时也会在循环中定时唤醒
                }
            }
            finally
            {
                lock.unlock();
            }
        }
        // 这里已经保证了availableSequence必然大于等于sequence，并且在存在依赖的场景中，被依赖消费者存在慢消费的话，
        // 会直接导致下游进入死循环（此时可能造成cpu升高，不像47行是 wait 而是 Busy Spin）。
        while ((availableSequence = dependentSequence.get()) < sequence)
        {   // 如果进入这里的循环，说明上一组消费者还未消费完毕
            barrier.checkAlert();
            ThreadHints.onSpinWait(); // 使用 Busy Spin 的方式等待上一组消费者完成消费
        }

        return availableSequence;
    }

    @Override
    public void signalAllWhenBlocking()
    {
        lock.lock();
        try
        {
            processorNotifyCondition.signalAll();  // 唤醒在processorNotifyCondition条件上等待的处理事件线程
        }
        finally
        {
            lock.unlock();
        }
    }

    @Override
    public String toString()
    {
        return "BlockingWaitStrategy{" +
            "processorNotifyCondition=" + processorNotifyCondition +
            '}';
    }
}
