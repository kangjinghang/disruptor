/*
 * Copyright 2012 LMAX Ltd.
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

import static java.util.Arrays.copyOf;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Provides static methods for managing a {@link SequenceGroup} object.
 */
class SequenceGroups
{
    static <T> void addSequences( // 使用 updater 把 sequencesToAdd 这个数组添加到 holder 这个对象持有的 Sequence数组里面，sequencesToAdd 这个数组 这个值设置为 cursor 的值
        final T holder,
        final AtomicReferenceFieldUpdater<T, Sequence[]> updater,
        final Cursored cursor,
        final Sequence... sequencesToAdd)
    {
        long cursorSequence;
        Sequence[] updatedSequences;
        Sequence[] currentSequences;

        do
        {
            currentSequences = updater.get(holder); // 得到追踪序列数组，即 AbstractSequencer 的 gatingSequences 字段
            updatedSequences = copyOf(currentSequences, currentSequences.length + sequencesToAdd.length); // 扩充数组
            cursorSequence = cursor.getCursor();

            int index = currentSequences.length;
            for (Sequence sequence : sequencesToAdd) // 循环新加入的追踪序列数组
            {
                sequence.set(cursorSequence); // 把 Cursored 接口得到的序列号设置给新加入的追踪序列
                updatedSequences[index++] = sequence; // 把新加入的追踪序列设置给扩充好的追踪序列数组
            }
        }
        while (!updater.compareAndSet(holder, currentSequences, updatedSequences)); // CAS 更新

        cursorSequence = cursor.getCursor();
        for (Sequence sequence : sequencesToAdd) // 再次循环新加入的追踪序列数组
        {
            sequence.set(cursorSequence); // 把 Cursored 接口得到的序列号设置给新加入的追踪序列
        }
    }

    static <T> boolean removeSequence(
        final T holder,
        final AtomicReferenceFieldUpdater<T, Sequence[]> sequenceUpdater,
        final Sequence sequence)
    {
        int numToRemove;
        Sequence[] oldSequences;
        Sequence[] newSequences;

        do
        {
            oldSequences = sequenceUpdater.get(holder);

            numToRemove = countMatching(oldSequences, sequence);

            if (0 == numToRemove)
            {
                break;
            }

            final int oldSize = oldSequences.length;
            newSequences = new Sequence[oldSize - numToRemove];

            for (int i = 0, pos = 0; i < oldSize; i++)
            {
                final Sequence testSequence = oldSequences[i];
                if (sequence != testSequence)
                {
                    newSequences[pos++] = testSequence;
                }
            }
        }
        while (!sequenceUpdater.compareAndSet(holder, oldSequences, newSequences));

        return numToRemove != 0;
    }

    private static <T> int countMatching(T[] values, final T toMatch)
    {
        int numToRemove = 0;
        for (T value : values)
        {
            if (value == toMatch) // Specifically uses identity
            {
                numToRemove++;
            }
        }
        return numToRemove;
    }
}
