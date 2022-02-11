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
 * Coordination barrier for tracking the cursor for publishers and sequence of
 * dependent {@link EventProcessor}s for processing a data structure
 */
public interface SequenceBarrier
{
    /**
     * Wait for the given sequence to be available for consumption.
     * 等待一个序列变为可用，然后消费这个序列。明显是给事件处理者使用的。
     * @param sequence to wait for EventProcessor传入的需要进行消费的起始sequence
     * @return the sequence up to which is available 这里并不保证返回值availableSequence一定等于given sequence，他们的大小关系取决于采用的WaitStrategy
     *         a.YieldingWaitStrategy：在自旋100次尝试后，会直接返回dependentSequence的最小seq，这时并不保证返回值>=given sequence
     *         b.BlockingWaitStrategy：则会阻塞等待given sequence可用为止，可用并不是说availableSequence == given sequence，而应当是指 >=
     *         c.SleepingWaitStrategy：首选会自旋100次，然后执行100次Thread.yield()，还是不行则LockSupport.parkNanos(1L)直到availableSequence >= given sequence
     * @throws AlertException       if a status change has occurred for the Disruptor
     * @throws InterruptedException if the thread needs awaking on a condition variable.
     * @throws TimeoutException     if a timeout occurs while waiting for the supplied sequence.
     */
    long waitFor(long sequence) throws AlertException, InterruptedException, TimeoutException;

    /**
     * Get the current cursor value that can be read.
     * 获取当前可以读取的序列值
     * @return value of the cursor for entries that have been published.
     */
    long getCursor();

    /**
     * The current alert status for the barrier.
     * 当前栅栏是否发过通知
     * @return true if in alert otherwise false.
     */
    boolean isAlerted();

    /**
     * Alert the {@link EventProcessor}s of a status change and stay in this status until cleared.
     */
    void alert(); // 通知事件处理者状态变化，然后停留在这个状态上，直到状态被清除

    /**
     * Clear the current alert status. 清除通知状态
     */
    void clearAlert();

    /**
     * Check if an alert has been raised and throw an {@link AlertException} if it has.
     * 检测是否发生了通知，如果已经发生了抛出AlertException异常
     * @throws AlertException if alert has been raised.
     */
    void checkAlert() throws AlertException;
}
