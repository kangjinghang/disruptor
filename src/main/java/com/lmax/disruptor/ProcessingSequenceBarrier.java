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


/**
 * {@link SequenceBarrier} handed out for gating {@link EventProcessor}s on a cursor sequence and optional dependent {@link EventProcessor}(s),
 * using the given WaitStrategy.
 */ // 需要依赖其他消费者的消费者在消费下一个消息时，会先等待在SequenceBarrier上，直到所有被依赖的消费者和RingBuffer的Sequence大于等于这个消费者的Sequence。当被依赖的消费者或RingBuffer的Sequence有变化时，会通知SequenceBarrier唤醒等待在它上面的消费者
final class ProcessingSequenceBarrier implements SequenceBarrier // 负责生产者和消费者之间通信，持有生产进度和最慢消费者进度
{
    private final WaitStrategy waitStrategy; // 等待策略
    private final Sequence dependentSequence;  // 这个域可能指向一个序列组中最小的序列值
    private volatile boolean alerted = false; // 报警状态
    private final Sequence cursorSequence; // RingBuffer的cursor，其实就是生产者进度Sequence
    private final Sequencer sequencer;

    ProcessingSequenceBarrier(
        final Sequencer sequencer,
        final WaitStrategy waitStrategy,
        final Sequence cursorSequence, // RingBuffer的cursor，其实就是生产者进度Sequence
        final Sequence[] dependentSequences) // 需要依赖的消费者的Sequence
    {
        this.sequencer = sequencer;
        this.waitStrategy = waitStrategy;
        this.cursorSequence = cursorSequence;
        if (0 == dependentSequences.length)
        {
            dependentSequence = cursorSequence; // dependentSequence = cursorSequence = cursor
        }
        else
        {
            dependentSequence = new FixedSequenceGroup(dependentSequences); // get 的时候就是 获取内部序列组中最小的序列值
        }
    }
    // 需要依赖其他消费者的消费者在消费下一个消息时，会先等待在SequenceBarrier上，直到所有被依赖的消费者和RingBuffer的Sequence大于等于这个消费者的Sequence。当被依赖的消费者或RingBuffer的Sequence有变化时，会通知SequenceBarrier唤醒等待在它上面的消费者。
    @Override
    public long waitFor(final long sequence)
        throws AlertException, InterruptedException, TimeoutException
    {
        checkAlert(); // 每次请求可用序列值时，会先检测报警状态，如果 alerted = true，会直接抛出异常，由上层处理
        // 然后根据等待策略来等待可用的序列值，无可消费消息是该接口可能会阻塞，具体逻辑由WaitStrategy实现。cursorSequence是生产进度，dependentSequence是最慢消费者消费进度，相互通信
        long availableSequence = waitStrategy.waitFor(sequence, cursorSequence, dependentSequence, this);
        // 比如 sequence =2 等待消费2号位置，availableSequence 可能返回1，也可能是2，也可能是10，具体是多少由 waitStrategy 决定，而且我们可以自定义waitStrategy，谁知道会返回什么呢
        if (availableSequence < sequence) // availableSequence 返回1
        {
            return availableSequence;  // 如果可用的序列值小于给定的要消费的序列值，那么直接返回
        }
        // 否则（availableSequence >=sequence），（比如 sequence =2，availableSequence 返回10）获取消费者可以消费的最大的可用序号，支持批处理效应，提升处理效率。
        // 当 availableSequence > sequence时，需要遍历 sequence --> availableSequence，找到最前一个准备就绪，【真正】可以被消费的event对应的seq。
        // 最小值为：sequence-1
        return sequencer.getHighestPublishedSequence(sequence, availableSequence); // 校验 waitStrategy 得到的 availableSequence，返回【真正】可以被消费的位置
    }

    @Override
    public long getCursor()
    {
        return dependentSequence.get();
    }

    @Override
    public boolean isAlerted()
    {
        return alerted;
    }

    @Override
    public void alert()
    {
        alerted = true; // 设置通知标记
        waitStrategy.signalAllWhenBlocking(); // 如果有线程以阻塞的方式等待序列，将其唤醒
    }

    @Override
    public void clearAlert()
    {
        alerted = false;
    }

    @Override
    public void checkAlert() throws AlertException
    {
        if (alerted)
        {
            throw AlertException.INSTANCE;
        }
    }
}