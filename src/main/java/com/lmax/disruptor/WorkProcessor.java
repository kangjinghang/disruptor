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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p>A {@link WorkProcessor} wraps a single {@link WorkHandler}, effectively consuming the sequence
 * and ensuring appropriate barriers.</p>
 *
 * <p>Generally, this will be used as part of a {@link WorkerPool}.</p>
 *
 * @param <T> event implementation storing the details for the work to processed.
 */
public final class WorkProcessor<T>
    implements EventProcessor
{
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE); // 当前 WorkProcessor 要去消费的位置
    private final RingBuffer<T> ringBuffer;
    private final SequenceBarrier sequenceBarrier;
    private final WorkHandler<? super T> workHandler;
    private final ExceptionHandler<? super T> exceptionHandler;
    // 多个处理者对同一个要处理事件的竞争，所以出现了一个workSequence，多个消费者共同使用，大家都从这个sequence里取得序列号，通过CAS保证线程安全
    private final Sequence workSequence; // 多个消费者 WorkProcessor 线程共同使用

    private final EventReleaser eventReleaser = new EventReleaser()
    {
        @Override
        public void release()
        {
            sequence.set(Long.MAX_VALUE);
        }
    };

    private final TimeoutHandler timeoutHandler;

    /**
     * Construct a {@link WorkProcessor}.
     *
     * @param ringBuffer       to which events are published.
     * @param sequenceBarrier  on which it is waiting.
     * @param workHandler      is the delegate to which events are dispatched.
     * @param exceptionHandler to be called back when an error occurs
     * @param workSequence     from which to claim the next event to be worked on.  It should always be initialised
     *                         as {@link Sequencer#INITIAL_CURSOR_VALUE}
     */
    public WorkProcessor(
        final RingBuffer<T> ringBuffer,
        final SequenceBarrier sequenceBarrier,
        final WorkHandler<? super T> workHandler,
        final ExceptionHandler<? super T> exceptionHandler,
        final Sequence workSequence)
    {
        this.ringBuffer = ringBuffer;
        this.sequenceBarrier = sequenceBarrier;
        this.workHandler = workHandler;
        this.exceptionHandler = exceptionHandler;
        this.workSequence = workSequence;

        if (this.workHandler instanceof EventReleaseAware)
        {
            ((EventReleaseAware) this.workHandler).setEventReleaser(eventReleaser);
        }

        timeoutHandler = (workHandler instanceof TimeoutHandler) ? (TimeoutHandler) workHandler : null;
    }

    @Override
    public Sequence getSequence()
    {
        return sequence;
    }

    @Override
    public void halt()
    {
        running.set(false);
        sequenceBarrier.alert();
    }

    @Override
    public boolean isRunning()
    {
        return running.get();
    }

    /**
     * It is ok to have another thread re-run this method after a halt().
     *
     * @throws IllegalStateException if this processor is already running
     */
    @Override
    public void run()
    {
        if (!running.compareAndSet(false, true)) // 状态设置与检测
        {
            throw new IllegalStateException("Thread is already running");
        }
        sequenceBarrier.clearAlert(); // 先清除序列栅栏的通知状态

        notifyStart();  // 如果workHandler实现了LifecycleAware，这里会对其进行一个启动通知

        boolean processedSequence = true; // 标志位，用来标志一次消费过程
        long cachedAvailableSequence = Long.MIN_VALUE; // 用来缓存消费者可以使用的RingBuffer最大序列号
        long nextSequence = sequence.get();  // 记录下当前这个 WorkProcessor 去 RingBuffer 取数据（要处理的事件）的序列号
        T event = null; // 因为各个消费线程都是共用的一个workSequence，所以通过原子类型设置方法来获取各自消费线程所要消费的下标（sequence，各自线程自己维护），达到了互斥消费
        while (true)
        {
            try
            {
                // if previous sequence was processed - fetch the next sequence and set
                // that we have successfully processed the previous sequence
                // typically, this will be true
                // this prevents the sequence getting too far forward if an exception
                // is thrown from the WorkHandler
                if (processedSequence) // 判断上一个事件是否已经处理完毕。每次消费开始执行
                {
                    processedSequence = false; // 如果处理完毕，重置标识
                    do
                    {
                        nextSequence = workSequence.get() + 1L;
                        sequence.set(nextSequence - 1L); // 给当前 sequence 设消费进度
                    }  // 因为各个消费线程都是共用的一个workSequence，所以通过CAS来获取各自消费线程所要消费的下标，达到了互斥消费
                    while (!workSequence.compareAndSet(nextSequence - 1L, nextSequence)); // CAS设置成功，说明当前 WorkProcessor 这个线程抢到了这个槽位
                }
                // 走到这里说明 nextSequence 这个槽位当前 WorkProcessor 这个线程抢到了，检查序列值是否需要申请。这一步是为了防止和事件生产者冲突。
                // 如果可使用的最大序列号cachedAvaliableSequence大于等于我们要使用的序列号nextSequence，直接从RingBuffer取数据；不然进入else
                if (cachedAvailableSequence >= nextSequence) // 上次的可用序号 cachedAvailableSequence 还没被消费到，还是可用的
                {
                    event = ringBuffer.get(nextSequence); // 从RingBuffer上获取事件
                    workHandler.onEvent(event); // 委托给workHandler处理事件
                    processedSequence = true; // 一次消费结束，设置事件处理完成标识
                }
                else
                { // 说明 cachedAvailableSequence < nextSequence，生产者还没生产到 nextSequence 这里，将最新的可用序号赋值给 cachedAvailableSequence
                    cachedAvailableSequence = sequenceBarrier.waitFor(nextSequence); // 如果需要申请，通过序列栅栏来申请可用的序列，等待生产者生产，获取到ringbuffer即生产者最大的可以使用的序列号
                }
            }
            catch (final TimeoutException e)
            { //  走到这里，processedSequence 还是 false
                notifyTimeout(sequence.get());
            }
            catch (final AlertException ex) // 处理通知
            { //  走到这里，processedSequence 还是 false
                if (!running.get()) //如果当前处理器被停止，那么退出主循环
                {
                    break;
                }
            }
            catch (final Throwable ex)
            {   // 处理异常
                // handle, mark as processed, unless the exception handler threw an exception
                exceptionHandler.handleEventException(ex, nextSequence, event);
                processedSequence = true; // 如果异常处理器不抛出异常的话，就认为事件处理完毕，设置事件处理完成标识
            }
        }
        // 退出主循环后，如果workHandler实现了LifecycleAware，这里会对其进行一个关闭通知
        notifyShutdown();
        // 设置当前处理器状态为停止
        running.set(false);
    }

    private void notifyTimeout(final long availableSequence)
    {
        try
        {
            if (timeoutHandler != null)
            {
                timeoutHandler.onTimeout(availableSequence);
            }
        }
        catch (Throwable e)
        {
            exceptionHandler.handleEventException(e, availableSequence, null);
        }
    }

    private void notifyStart()
    {
        if (workHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) workHandler).onStart();
            }
            catch (final Throwable ex)
            {
                exceptionHandler.handleOnStartException(ex);
            }
        }
    }

    private void notifyShutdown()
    {
        if (workHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) workHandler).onShutdown();
            }
            catch (final Throwable ex)
            {
                exceptionHandler.handleOnShutdownException(ex);
            }
        }
    }
}
