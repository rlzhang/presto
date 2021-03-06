/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.execution;

import com.facebook.presto.OutputBuffers;
import com.facebook.presto.PagePartitionFunction;
import com.facebook.presto.execution.StateMachine.StateChangeListener;
import com.facebook.presto.operator.Page;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.airlift.units.DataSize;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.facebook.presto.OutputBuffers.INITIAL_EMPTY_OUTPUT_BUFFERS;
import static com.facebook.presto.execution.BufferResult.emptyResults;
import static com.facebook.presto.execution.SharedBuffer.BufferState.FINISHED;
import static com.facebook.presto.execution.SharedBuffer.BufferState.FLUSHING;
import static com.facebook.presto.execution.SharedBuffer.BufferState.NO_MORE_BUFFERS;
import static com.facebook.presto.execution.SharedBuffer.BufferState.NO_MORE_PAGES;
import static com.facebook.presto.execution.SharedBuffer.BufferState.OPEN;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.Futures.immediateFuture;

@ThreadSafe
public class SharedBuffer
{
    public enum BufferState
    {
        /**
         * Additional buffers can be added.
         * Any next state is allowed.
         */
        OPEN(true, true),
        /**
         * No more buffers can be added.
         * Next state is {@link #FLUSHING}.
         */
        NO_MORE_BUFFERS(true, false),
        /**
         * No more pages can be added.
         * Next state is {@link #FLUSHING}.
         */
        NO_MORE_PAGES(false, true),
        /**
         * No more pages or buffers can be added, and buffer is waiting
         * for the final pages to be consumed.
         * Next state is {@link #FINISHED}.
         */
        FLUSHING(false, false),
        /**
         * No more buffers can be added and all pages have been consumed.
         * This is the terminal state.
         */
        FINISHED(false, false);

        private final boolean newPagesAllowed;
        private final boolean newBuffersAllowed;

        BufferState(boolean newPagesAllowed, boolean newBuffersAllowed)
        {
            this.newPagesAllowed = newPagesAllowed;
            this.newBuffersAllowed = newBuffersAllowed;
        }

        public boolean canAddPages()
        {
            return newPagesAllowed;
        }

        public boolean canAddBuffers()
        {
            return newBuffersAllowed;
        }
    }

    private final long maxBufferedBytes;

    @GuardedBy("this")
    private OutputBuffers outputBuffers = INITIAL_EMPTY_OUTPUT_BUFFERS;

    @GuardedBy("this")
    private long bufferedBytes;
    @GuardedBy("this")
    private final LinkedList<Page> masterBuffer = new LinkedList<>();
    @GuardedBy("this")
    private final BlockingQueue<QueuedPage> queuedPages = new LinkedBlockingQueue<>();
    @GuardedBy("this")
    private final AtomicLong masterSequenceId = new AtomicLong();
    @GuardedBy("this")
    private final ConcurrentMap<String, NamedBuffer> namedBuffers = new ConcurrentHashMap<>();
    @GuardedBy("this")
    private Set<String> abortedBuffers = new HashSet<>();

    private final StateMachine<BufferState> state;

    @GuardedBy("this")
    private List<GetBufferResult> stateChangeListeners = new ArrayList<>();

    private final AtomicLong pagesAdded = new AtomicLong();

    public SharedBuffer(TaskId taskId, Executor executor, DataSize maxBufferSize)
    {
        checkNotNull(taskId, "taskId is null");
        checkNotNull(executor, "executor is null");
        state = new StateMachine<>(taskId + "-buffer", executor, OPEN);

        checkNotNull(maxBufferSize, "maxBufferSize is null");
        checkArgument(maxBufferSize.toBytes() > 0, "maxBufferSize must be at least 1");
        this.maxBufferedBytes = maxBufferSize.toBytes();
    }

    public void addStateChangeListener(StateChangeListener<BufferState> stateChangeListener)
    {
        state.addStateChangeListener(stateChangeListener);
    }

    public boolean isFinished()
    {
        return state.get() == FINISHED;
    }

    public SharedBufferInfo getInfo()
    {
        //
        // NOTE: this code must be lock free to we are not hanging state machine updates
        //
        checkState(!Thread.holdsLock(this), "Thread must NOT hold a lock on the %s", SharedBuffer.class.getSimpleName());

        ImmutableList.Builder<BufferInfo> infos = ImmutableList.builder();
        for (NamedBuffer namedBuffer : namedBuffers.values()) {
            infos.add(namedBuffer.getInfo());
        }
        return new SharedBufferInfo(state.get(), masterSequenceId.get(), pagesAdded.get(), infos.build());
    }

    public synchronized void setOutputBuffers(OutputBuffers newOutputBuffers)
    {
        checkNotNull(newOutputBuffers, "newOutputBuffers is null");
        // ignore buffers added after query finishes, which can happen when a query is canceled
        // also ignore old versions, which is normal
        if (state.get() == FINISHED || outputBuffers.getVersion() >= newOutputBuffers.getVersion()) {
            return;
        }

        // verify this is valid state change
        SetView<String> missingBuffers = Sets.difference(outputBuffers.getBuffers().keySet(), newOutputBuffers.getBuffers().keySet());
        checkArgument(missingBuffers.isEmpty(), "newOutputBuffers does not have existing buffers %s", missingBuffers);
        checkArgument(!outputBuffers.isNoMoreBufferIds() || newOutputBuffers.isNoMoreBufferIds(), "Expected newOutputBuffers to have noMoreBufferIds set");
        outputBuffers = newOutputBuffers;

        // add the new buffers
        for (Entry<String, PagePartitionFunction> entry : outputBuffers.getBuffers().entrySet()) {
            String bufferId = entry.getKey();
            if (!namedBuffers.containsKey(bufferId)) {
                checkState(state.get().canAddBuffers(), "Cannot add buffers to %s", SharedBuffer.class.getSimpleName());
                NamedBuffer namedBuffer = new NamedBuffer(bufferId, entry.getValue());
                // the buffer may have been aborted before the creation message was received
                if (abortedBuffers.contains(bufferId)) {
                    namedBuffer.abort();
                }
                namedBuffers.put(bufferId, namedBuffer);
            }
        }

        // update state if no more buffers is set
        if (outputBuffers.isNoMoreBufferIds()) {
            state.compareAndSet(OPEN, NO_MORE_BUFFERS);
            state.compareAndSet(NO_MORE_PAGES, FLUSHING);
        }

        updateState();
    }

    public synchronized ListenableFuture<?> enqueue(Page page)
    {
        checkNotNull(page, "page is null");

        // ignore pages after no more pages is set
        // this can happen with a limit query
        if (!state.get().canAddPages()) {
            return immediateFuture(true);
        }

        // is there room in the buffer
        if (bufferedBytes < maxBufferedBytes) {
            addInternal(page);
            return immediateFuture(true);
        }

        QueuedPage queuedPage = new QueuedPage(page);
        queuedPages.add(queuedPage);
        updateState();
        return queuedPage.getFuture();
    }

    private synchronized void addInternal(Page page)
    {
        // add page
        masterBuffer.add(page);
        pagesAdded.incrementAndGet();
        bufferedBytes += page.getDataSize().toBytes();

        processPendingReads();
    }

    public synchronized ListenableFuture<BufferResult> get(String outputId, long startingSequenceId, DataSize maxSize)
    {
        checkNotNull(outputId, "outputId is null");
        checkArgument(maxSize.toBytes() > 0, "maxSize must be at least 1 byte");

        // if no buffers can be added, and the requested buffer does not exist, return a closed empty result
        // this can happen with limit queries
        if (!state.get().canAddBuffers() && namedBuffers.get(outputId) == null) {
            return immediateFuture(emptyResults(0, true));
        }

        // return a future for data
        GetBufferResult getBufferResult = new GetBufferResult(outputId, startingSequenceId, maxSize);
        stateChangeListeners.add(getBufferResult);
        updateState();
        return getBufferResult.getFuture();
    }

    public synchronized List<Page> getPagesInternal(DataSize maxSize, long sequenceId)
    {
        long maxBytes = maxSize.toBytes();
        List<Page> pages = new ArrayList<>();
        long bytes = 0;

        int listOffset = Ints.checkedCast(sequenceId - masterSequenceId.get());
        while (listOffset < masterBuffer.size()) {
            Page page = masterBuffer.get(listOffset++);
            bytes += page.getDataSize().toBytes();
            // break (and don't add) if this page would exceed the limit
            if (!pages.isEmpty() && bytes > maxBytes) {
                break;
            }
            pages.add(page);
        }
        return ImmutableList.copyOf(pages);
    }

    public synchronized void abort(String outputId)
    {
        checkNotNull(outputId, "outputId is null");

        abortedBuffers.add(outputId);

        NamedBuffer namedBuffer = namedBuffers.get(outputId);
        if (namedBuffer != null) {
            namedBuffer.abort();
        }

        updateState();
    }

    public synchronized void setNoMorePages()
    {
        if (state.compareAndSet(OPEN, NO_MORE_PAGES) || state.compareAndSet(NO_MORE_BUFFERS, FLUSHING)) {
            updateState();
        }
    }

    /**
     * Destroys the buffer, discarding all pages.
     */
    public synchronized void destroy()
    {
        state.set(FINISHED);

        // clear the buffer
        masterBuffer.clear();
        bufferedBytes = 0;

        // free queued page waiters
        for (QueuedPage queuedPage : queuedPages) {
            queuedPage.getFuture().set(null);
        }
        queuedPages.clear();

        for (NamedBuffer namedBuffer : namedBuffers.values()) {
            namedBuffer.abort();
        }
        processPendingReads();
    }

    private void checkFlushComplete()
    {
        checkState(Thread.holdsLock(this), "Thread must hold a lock on the %s", SharedBuffer.class.getSimpleName());

        if (state.get() == FLUSHING) {
            for (NamedBuffer namedBuffer : namedBuffers.values()) {
                if (!namedBuffer.checkCompletion()) {
                    return;
                }
            }
            destroy();
        }
    }

    private void updateState()
    {
        checkState(Thread.holdsLock(this), "Thread must hold a lock on the %s", SharedBuffer.class.getSimpleName());

        try {
            processPendingReads();

            BufferState state = this.state.get();
            if (state == FINISHED) {
                return;
            }

            if (!state.canAddPages()) {
                // discard queued pages (not officially in the buffer)
                for (QueuedPage queuedPage : queuedPages) {
                    queuedPage.getFuture().set(null);
                }
                queuedPages.clear();
            }

            // advanced master queue
            if (!state.canAddBuffers() && !namedBuffers.isEmpty()) {
                // advance master sequence id
                long oldMasterSequenceId = masterSequenceId.get();
                long newMasterSequenceId = Long.MAX_VALUE;
                for (NamedBuffer namedBuffer : namedBuffers.values()) {
                    newMasterSequenceId = Math.min(namedBuffer.getSequenceId(), newMasterSequenceId);
                }
                masterSequenceId.set(newMasterSequenceId);

                // drop consumed pages
                int pagesToRemove = Ints.checkedCast(newMasterSequenceId - oldMasterSequenceId);
                checkState(pagesToRemove >= 0,
                        "Master sequence id moved backwards: oldMasterSequenceId=%s, newMasterSequenceId=%s",
                        oldMasterSequenceId,
                        newMasterSequenceId);

                for (int i = 0; i < pagesToRemove; i++) {
                    Page page = masterBuffer.removeFirst();
                    bufferedBytes -= page.getDataSize().toBytes();
                }

                // refill buffer from queued pages
                while (!queuedPages.isEmpty() && bufferedBytes < maxBufferedBytes) {
                    QueuedPage queuedPage = queuedPages.remove();
                    addInternal(queuedPage.getPage());
                    queuedPage.getFuture().set(null);
                }
            }

            // remove any completed buffers
            if (!state.canAddPages()) {
                for (NamedBuffer namedBuffer : namedBuffers.values()) {
                    namedBuffer.checkCompletion();
                }
            }
        }
        finally {
            checkFlushComplete();
        }
    }

    private void processPendingReads()
    {
        checkState(Thread.holdsLock(this), "Thread must hold a lock on the %s", SharedBuffer.class.getSimpleName());

        for (GetBufferResult getBufferResult : ImmutableList.copyOf(stateChangeListeners)) {
            if (getBufferResult.execute()) {
                stateChangeListeners.remove(getBufferResult);
            }
        }
    }

    @ThreadSafe
    private final class NamedBuffer
    {
        private final String bufferId;
        private final PagePartitionFunction partitionFunction;

        private final AtomicLong sequenceId = new AtomicLong();
        private final AtomicBoolean finished = new AtomicBoolean();

        private NamedBuffer(String bufferId, PagePartitionFunction partitionFunction)
        {
            this.bufferId = bufferId;
            this.partitionFunction = partitionFunction;
        }

        public BufferInfo getInfo()
        {
            //
            // NOTE: this code must be lock free to we are not hanging state machine updates
            //
            checkState(!Thread.holdsLock(this), "Thread must NOT hold a lock on the %s", SharedBuffer.class.getSimpleName());

            long sequenceId = this.sequenceId.get();
            if (finished.get()) {
                return new BufferInfo(bufferId, true, 0, sequenceId);
            }

            int size = Math.max(Ints.checkedCast(pagesAdded.get() + queuedPages.size() - sequenceId), 0);
            return new BufferInfo(bufferId, finished.get(), size, sequenceId);
        }

        public long getSequenceId()
        {
            checkState(Thread.holdsLock(SharedBuffer.this), "Thread must hold a lock on the %s", SharedBuffer.class.getSimpleName());

            return sequenceId.get();
        }

        public BufferResult getPages(long startingSequenceId, DataSize maxSize)
        {
            checkState(Thread.holdsLock(SharedBuffer.this), "Thread must hold a lock on the %s", SharedBuffer.class.getSimpleName());
            checkArgument(maxSize.toBytes() > 0, "maxSize must be at least 1 byte");

            long sequenceId = this.sequenceId.get();
            checkArgument(startingSequenceId >= sequenceId, "startingSequenceId is before the beginning of the buffer");

            // acknowledge previous pages
            if (startingSequenceId > sequenceId) {
                this.sequenceId.set(startingSequenceId);
                sequenceId = startingSequenceId;
            }

            if (checkCompletion()) {
                return emptyResults(startingSequenceId, true);
            }

            List<Page> pages = getPagesInternal(maxSize, sequenceId);
            return new BufferResult(startingSequenceId, startingSequenceId + pages.size(), false, pages, partitionFunction);
        }

        public void abort()
        {
            checkState(Thread.holdsLock(SharedBuffer.this), "Thread must hold a lock on the %s", SharedBuffer.class.getSimpleName());

            finished.set(true);
        }

        public boolean checkCompletion()
        {
            checkState(Thread.holdsLock(SharedBuffer.this), "Thread must hold a lock on the %s", SharedBuffer.class.getSimpleName());
            // WARNING: finish must short circuit this call, or the call to checkFlushComplete below will cause an infinite recursion
            if (finished.get()) {
                return true;
            }

            if (!state.get().canAddPages() && sequenceId.get() >= pagesAdded.get()) {
                // WARNING: finish must set before the call to checkFlushComplete of the short circuit above will not trigger and the code enter an infinite recursion
                finished.set(true);

                // check if master buffer is finished
                checkFlushComplete();
            }
            return finished.get();
        }

        @Override
        public String toString()
        {
            return Objects.toStringHelper(this)
                    .add("bufferId", bufferId)
                    .add("sequenceId", sequenceId.get())
                    .add("finished", finished.get())
                    .toString();
        }
    }

    @Immutable
    private static final class QueuedPage
    {
        private final Page page;
        private final SettableFuture<?> future = SettableFuture.create();

        private QueuedPage(Page page)
        {
            this.page = page;
        }

        private Page getPage()
        {
            return page;
        }

        private SettableFuture<?> getFuture()
        {
            return future;
        }
    }

    @Immutable
    private class GetBufferResult
    {
        private final SettableFuture<BufferResult> future = SettableFuture.create();

        private final String outputId;
        private final long startingSequenceId;
        private final DataSize maxSize;

        public GetBufferResult(String outputId, long startingSequenceId, DataSize maxSize)
        {
            this.outputId = outputId;
            this.startingSequenceId = startingSequenceId;
            this.maxSize = maxSize;
        }

        public SettableFuture<BufferResult> getFuture()
        {
            return future;
        }

        public boolean execute()
        {
            checkState(Thread.holdsLock(SharedBuffer.this), "Thread must hold a lock on the %s", SharedBuffer.class.getSimpleName());

            if (future.isDone()) {
                return true;
            }

            try {
                NamedBuffer namedBuffer = namedBuffers.get(outputId);

                // if buffer is finished return an empty page
                // this could be a request for a buffer that never existed, but that is ok since the buffer
                // could have been destroyed before the creation message was received
                if (state.get() == FINISHED) {
                    future.set(emptyResults(namedBuffer == null ? 0 : namedBuffer.getSequenceId(), true));
                    return true;
                }

                // buffer doesn't exist yet
                if (namedBuffer == null) {
                    return false;
                }

                // if request is for pages before the current position, just return an empty page
                if (startingSequenceId < namedBuffer.getSequenceId()) {
                    future.set(emptyResults(startingSequenceId, false));
                    return true;
                }

                // read pages from the buffer
                BufferResult bufferResult = namedBuffer.getPages(startingSequenceId, maxSize);

                // if this was the last page, we're done
                checkFlushComplete();

                // if we got an empty result, wait for more pages
                if (bufferResult.isEmpty() && !bufferResult.isBufferClosed()) {
                    return false;
                }

                future.set(bufferResult);
            }
            catch (Throwable throwable) {
                future.setException(throwable);
            }
            return true;
        }
    }
}
