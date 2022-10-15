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
 * Strategy employed for making {@link EventProcessor}s wait on a cursor {@link Sequence}.
 */
public interface WaitStrategy
{
    /** 第一重判断：消费者消费的序号不能超过当前生产者消费当前生产的序号。第二重判断：消费者消费的序号不能超过【其前面依赖的消费者】消费的序号
     * Wait for the given sequence to be available.  It is possible for this method to return a value
     * less than the sequence number supplied depending on the implementation of the WaitStrategy.  A common
     * use for this is to signal a timeout.  Any EventProcessor that is using a WaitStrategy to get notifications
     * about message becoming available should remember to handle this case.  The {@link BatchEventProcessor} explicitly
     * handles this case and will signal a timeout if required.
     * 等待给定的sequence变为可用，比如 sequence=2，申请2号位置来消费，这个方法返回10，10之前的都可以消费了（不止是2之前的）
     * @param sequence          to be waited on. 消费者等待消费的序列值
     * @param cursor            the main sequence from ringbuffer. Wait/notify strategies will
     *                          need this as it's the only sequence that is also notified upon update. 事件发布者的生产进度
     * @param dependentSequence on which to wait. 事件处理者依赖的最小消费进度序列，第一个消费者即前面不依赖任何消费者的消费者，dependentSequence 就是生产者游标，有依赖其他消费者的消费者，dependentSequence 就是所依赖的消费者的 sequence
     * @param barrier           the processor is waiting on. 序列栅栏
     * @return the sequence that is available which may be greater than the requested sequence. 对事件处理者来说可用的序列值，可能会比申请的序列值大
     * @throws AlertException       if the status of the Disruptor has changed.
     * @throws InterruptedException if the thread is interrupted.
     * @throws TimeoutException if a timeout occurs before waiting completes (not used by some strategies)
     */
    long waitFor(long sequence, Sequence cursor, Sequence dependentSequence, SequenceBarrier barrier)
        throws AlertException, InterruptedException, TimeoutException;

    /**
     * Implementations should signal the waiting {@link EventProcessor}s that the cursor has advanced.
     */
    void signalAllWhenBlocking(); // 当发布事件成功后会调用这个方法来通知等待的事件处理者序列可用了
}
