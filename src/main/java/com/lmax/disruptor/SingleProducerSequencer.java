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

import java.util.concurrent.locks.LockSupport;

import com.lmax.disruptor.util.Util;

abstract class SingleProducerSequencerPad extends AbstractSequencer
{
    protected long p1, p2, p3, p4, p5, p6, p7;

    SingleProducerSequencerPad(int bufferSize, WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }
}

abstract class SingleProducerSequencerFields extends SingleProducerSequencerPad
{
    SingleProducerSequencerFields(int bufferSize, WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }

    /**
     * Set to -1 as sequence starting point
     */
    long nextValue = Sequence.INITIAL_VALUE; // 事件发布者申请的要生产到的位置的序列值
    long cachedValue = Sequence.INITIAL_VALUE; // 事件处理者（可能是多个）都处理完成（消费完）即消费最慢的处理者的序列值
}

/**
 * <p>Coordinator for claiming sequences for access to a data structure while tracking dependent {@link Sequence}s.
 * Not safe for use from multiple threads as it does not implement any barriers.</p>
 *
 * <p>* Note on {@link Sequencer#getCursor()}:  With this sequencer the cursor value is updated after the call
 * to {@link Sequencer#publish(long)} is made.</p>
 */

public final class SingleProducerSequencer extends SingleProducerSequencerFields
{
    protected long p1, p2, p3, p4, p5, p6, p7;

    /**
     * Construct a Sequencer with the selected wait strategy and buffer size.
     *
     * @param bufferSize   the size of the buffer that this will sequence over.
     * @param waitStrategy for those waiting on sequences.
     */
    public SingleProducerSequencer(int bufferSize, WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }

    /**
     * @see Sequencer#hasAvailableCapacity(int)
     */
    @Override
    public boolean hasAvailableCapacity(int requiredCapacity)
    {
        return hasAvailableCapacity(requiredCapacity, false);
    }

    private boolean hasAvailableCapacity(int requiredCapacity, boolean doStore)
    {
        long nextValue = this.nextValue;
        // 当前序列的nextValue + requiredCapacity是事件发布者要申请的序列值。当前序列的cachedValue记录的是之前事件处理者申请的序列值。
        // 想一下一个环形队列，事件发布者在什么情况下才能申请一个序列呢？事件发布者当前的位置在事件处理者前面，并且不能从事件处理者后面追上事件处理者（因为是环形）
        // 即 事件发布者要申请的序列值大于事件处理者之前的序列值 且 事件发布者要申请的序列值减去环的长度要小于事件处理者的序列值
        // 如果满足这个条件，即使不知道当前事件处理者的序列值，也能确保事件发布者可以申请给定的序列。
        // 如果不满足这个条件，就需要查看一下当前事件处理者的最小的序列值（因为可能有多个事件处理者），
        // 如果当前要申请的序列值比当前事件处理者的最小序列值大了一圈（从后面追上了），那就不能申请了（申请的话会覆盖没被消费的事件），
        // 也就是说没有可用的空间（用来发布事件）了
        long wrapPoint = (nextValue + requiredCapacity) - bufferSize; // 要申请序列值的上一圈的序列值，wrapPoint是负数，可以一直生产，如果是一个大于0的数，wrapPoint要小于等于多个消费者线程中消费的最小的序列号，即cachedValue的值
        long cachedGatingSequence = this.cachedValue;
        // wrapPoint > cachedGatingSequence == true 的话，就要被套圈了
        // 如果wrapPoint比最慢消费者序号还大，代表生产者绕了一圈后又追赶上了消费者，这时候就不能继续生产了，否则把消费者还没消费的消息事件覆盖
        // 如果wrapPoint <= 上次最慢消费者序号，说明还是连上次最慢消费者序号都没使用完，不用进入下面的if代码块，直接返回nextSequence就行了
        // 这样做目的：每次都去获取真实的最慢消费线程序号比较浪费资源，而是获取一批可用序号后，生产者只有使用完后，才继续获取当前最慢消费线程最小序号，重新获取最新资源
        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)
        {
            if (doStore)
            {
                cursor.setVolatile(nextValue);  // StoreLoad fence
            }
            // 所有跟踪序列的序列值和nextValue之中取的最小值
            long minSequence = Util.getMinimumSequence(gatingSequences, nextValue);
            this.cachedValue = minSequence; // 更新缓存，事件处理者（可能是多个）都处理完成的序列值 为 minSequence

            if (wrapPoint > minSequence) // true的话，就要被套圈了
            {
                return false;
            }
        }

        return true;
    }

    /**
     * @see Sequencer#next()
     */
    @Override
    public long next()
    {
        return next(1);
    }

    /**
     * @see Sequencer#next(int)
     */
    @Override
    public long next(int n)
    {
        if (n < 1) // n表示此次生产者期望获取多少个序号，通常是1
        {
            throw new IllegalArgumentException("n must be > 0");
        }

        long nextValue = this.nextValue;
        // 这里n一般是1，代表申请1个可用槽位，nextValue + n 就代表了期望申请的可用槽位序号
        long nextSequence = nextValue + n;
        long wrapPoint = nextSequence - bufferSize; // 减掉RingBuffer的bufferSize值，用于判断是否出现‘绕圈覆盖’
        long cachedGatingSequence = this.cachedValue; // cachedValue缓存【之前】获取的最慢消费者消费到的槽位序号，如果上次更新的cachedValue还没被使用完，那么就继续用上次的序号
        // 如果wrapPoint比最慢消费者序号还大，代表生产者绕了一圈后又追赶上了消费者，这时候就不能继续生产了，否则把消费者还没消费的消息事件覆盖
        // 如果wrapPoint <= 上次最慢消费者序号，说明还是连上次最慢消费者序号都没使用完，不用进入下面的if代码块，直接返回nextSequence就行了
        // 这样做目的：每次都去获取真实的最慢消费线程序号比较浪费资源，而是获取一批可用序号后，生产者只有使用完后，才继续获取当前最慢消费线程最小序号，重新获取最新资源
        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)
        {
            // cursor代表当前已经生产完成的序号，这里采用UNSAFE.putLongVolatile()插入一个StoreLoad内存屏障，主要保证cursor的真实值对所有的消费线程可见，避免不可见下消费线程无法消费问题
            cursor.setVolatile(nextValue);  // StoreLoad fence

            long minSequence; // 判断wrapPoint是否大于真实的消费者线程最小的序列号，如果大于，不能写入，继续等待
            while (wrapPoint > (minSequence = Util.getMinimumSequence(gatingSequences, nextValue))) // 不能被套圈
            {   // 可以看到，next()方法是一个阻塞接口，如果一直获取不到可用资源，就会一直阻塞在这里
                LockSupport.parkNanos(1L); // TODO: Use waitStrategy to spin? 如果获取最新最慢消费线程最小序号后，依然没有可用资源，阻塞等待一下，然后重试
            }
            // 有可用资源时，将当前最慢消费序号缓存到cachedValue中，下次再申请时就可不必再进入if块中获取真实的最慢消费线程序号，只有这次获取到的被生产者使用完才会继续进入if块
            this.cachedValue = minSequence;
        }
        // 申请成功，将nextValue重新设置，缓存生产者最大生产序列号，下次再申请时继续在该值基础上申请
        this.nextValue = nextSequence;

        return nextSequence;
    }

    /**
     * @see Sequencer#tryNext()
     */
    @Override
    public long tryNext() throws InsufficientCapacityException
    {
        return tryNext(1);
    }

    /**
     * @see Sequencer#tryNext(int)
     */
    @Override
    public long tryNext(int n) throws InsufficientCapacityException
    {
        if (n < 1)
        {
            throw new IllegalArgumentException("n must be > 0");
        }

        if (!hasAvailableCapacity(n, true))
        {
            throw InsufficientCapacityException.INSTANCE;
        }

        long nextSequence = this.nextValue += n;

        return nextSequence;
    }

    /**
     * @see Sequencer#remainingCapacity()
     */
    @Override
    public long remainingCapacity()
    {
        long nextValue = this.nextValue;

        long consumed = Util.getMinimumSequence(gatingSequences, nextValue);
        long produced = nextValue;
        return getBufferSize() - (produced - consumed); // 环形队列的容量减去事件发布者与事件处理者的序列差
    }

    /**
     * @see Sequencer#claim(long) 声明一个序列，这个方法只在初始化RingBuffer的时候被调用
     */
    @Override
    public void claim(long sequence)
    {
        this.nextValue = sequence;
    }

    /**
     * @see Sequencer#publish(long)
     */
    @Override
    public void publish(long sequence)
    {
        cursor.set(sequence); // 先设置内部游标值
        waitStrategy.signalAllWhenBlocking(); // 然后唤醒等待的事件处理者。
    }

    /**
     * @see Sequencer#publish(long, long)
     */
    @Override
    public void publish(long lo, long hi)
    {
        publish(hi);
    }

    /**
     * @see Sequencer#isAvailable(long)
     */
    @Override
    public boolean isAvailable(long sequence)
    {
        return sequence <= cursor.get();
    }

    @Override
    public long getHighestPublishedSequence(long lowerBound, long availableSequence)
    {
        return availableSequence; // 单生产者，availableSequence就是已经生产好的消息的最大序列值
    }
}
