/*
 * GRAKN.AI - THE KNOWLEDGE GRAPH
 * Copyright (C) 2019 Grakn Labs Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package grakn.core.graph.diskstorage.locking.consistentkey;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import grakn.core.graph.diskstorage.BackendException;
import grakn.core.graph.diskstorage.BaseTransactionConfig;
import grakn.core.graph.diskstorage.Entry;
import grakn.core.graph.diskstorage.StaticBuffer;
import grakn.core.graph.diskstorage.keycolumnvalue.KeyColumnValueStore;
import grakn.core.graph.diskstorage.keycolumnvalue.KeySliceQuery;
import grakn.core.graph.diskstorage.keycolumnvalue.StoreTransaction;
import grakn.core.graph.diskstorage.locking.LocalLockMediator;
import grakn.core.graph.diskstorage.locking.Locker;
import grakn.core.graph.diskstorage.locking.PermanentLockingException;
import grakn.core.graph.diskstorage.util.BackendOperation;
import grakn.core.graph.diskstorage.util.BufferUtil;
import grakn.core.graph.diskstorage.util.KeyColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * A {@link StoreTransaction} that supports locking via
 * {@link LocalLockMediator} and writing and reading lock records in a
 * {@link ExpectedValueCheckingStore}.
 * <p>
 * <p>
 * <b>This class is not safe for concurrent use by multiple threads.
 * Multithreaded access must be prevented or externally synchronized.</b>
 */
public class ExpectedValueCheckingTransaction implements StoreTransaction {

    private static final Logger LOG = LoggerFactory.getLogger(ExpectedValueCheckingTransaction.class);

    /**
     * This variable starts false.  It remains false during the
     * locking stage of a transaction.  It is set to true at the
     * beginning of the first mutate/mutateMany call in a transaction
     * (before performing any writes to the backing store).
     */
    private boolean isMutationStarted;

    /**
     * Transaction for reading and writing locking-related metadata. Also used
     * for reading expected values provided as arguments to
     * {@link KeyColumnValueStore#acquireLock(StaticBuffer, StaticBuffer, StaticBuffer, StoreTransaction)}
     */
    private final StoreTransaction strongConsistentTx;

    /**
     * Transaction for reading and writing client data. No guarantees about
     * consistency strength.
     */
    private final StoreTransaction inconsistentTx;
    private final Duration maxReadTime;

    private final Map<ExpectedValueCheckingStore, Map<KeyColumn, StaticBuffer>> expectedValuesByStore = new HashMap<>();

    public ExpectedValueCheckingTransaction(StoreTransaction inconsistentTx, StoreTransaction strongConsistentTx, Duration maxReadTime) {
        this.inconsistentTx = inconsistentTx;
        this.strongConsistentTx = strongConsistentTx;
        this.maxReadTime = maxReadTime;
    }

    @Override
    public void rollback() throws BackendException {
        deleteAllLocks();
        inconsistentTx.rollback();
        strongConsistentTx.rollback();
    }

    @Override
    public void commit() throws BackendException {
        inconsistentTx.commit();
        deleteAllLocks();
        strongConsistentTx.commit();
    }

    /**
     * Tells whether this transaction has been used in a
     * {@link ExpectedValueCheckingStore#mutate(StaticBuffer, List, List, StoreTransaction)}
     * call. When this returns true, the transaction is no longer allowed in
     * calls to
     * {@link ExpectedValueCheckingStore#acquireLock(StaticBuffer, StaticBuffer, StaticBuffer, StoreTransaction)}.
     *
     * @return False until
     * {@link ExpectedValueCheckingStore#mutate(StaticBuffer, List, List, StoreTransaction)}
     * is called on this transaction instance. Returns true forever
     * after.
     */
    public boolean isMutationStarted() {
        return isMutationStarted;
    }

    @Override
    public BaseTransactionConfig getConfiguration() {
        return inconsistentTx.getConfiguration();
    }

    public StoreTransaction getInconsistentTx() {
        return inconsistentTx;
    }

    public StoreTransaction getConsistentTx() {
        return strongConsistentTx;
    }

    void storeExpectedValue(ExpectedValueCheckingStore store, KeyColumn lockID, StaticBuffer value) {
        Preconditions.checkNotNull(store);
        Preconditions.checkNotNull(lockID);

        lockedOn(store);
        Map<KeyColumn, StaticBuffer> m = expectedValuesByStore.get(store);
        if (m.containsKey(lockID)) {
            LOG.debug("Multiple expected values for {}: keeping initial value {} and discarding later value {}",
                    lockID, m.get(lockID), value);
        } else {
            m.put(lockID, value);
            LOG.debug("Store expected value for {}: {}", lockID, value);
        }
    }

    /**
     * If {@code !}{@link #isMutationStarted()}, check all locks and expected
     * values, then mark the transaction as started.
     * <p>
     * If {@link #isMutationStarted()}, this does nothing.
     *
     * @return true if this transaction holds at least one lock, false if the
     * transaction holds no locks
     */
    boolean prepareForMutations() throws BackendException {
        if (!isMutationStarted()) {
            checkAllLocks();
            checkAllExpectedValues();
            mutationStarted();
        }
        return !expectedValuesByStore.isEmpty();
    }

    /**
     * Check all locks attempted by earlier
     * {@link KeyColumnValueStore#acquireLock(StaticBuffer, StaticBuffer, StaticBuffer, StoreTransaction)}
     * calls using this transaction.
     */
    private void checkAllLocks() throws BackendException {
        StoreTransaction lt = getConsistentTx();
        for (ExpectedValueCheckingStore store : expectedValuesByStore.keySet()) {
            Locker locker = store.getLocker();
            // Ignore locks on stores without a locker
            if (null == locker)
                continue;
            locker.checkLocks(lt);
        }
    }

    /**
     * Check that all expected values saved from earlier
     * {@link KeyColumnValueStore#acquireLock(StaticBuffer, StaticBuffer, StaticBuffer, StoreTransaction)}
     * calls using this transaction.
     */
    private void checkAllExpectedValues() throws BackendException {
        for (ExpectedValueCheckingStore store : expectedValuesByStore.keySet()) {
            final Map<KeyColumn, StaticBuffer> m = expectedValuesByStore.get(store);
            for (KeyColumn kc : m.keySet()) {
                checkSingleExpectedValue(kc, m.get(kc), store);
            }
        }
    }

    /**
     * Signals the transaction that it has been used in a call to
     * {@link ExpectedValueCheckingStore#mutate(StaticBuffer, List, List, StoreTransaction)}
     * . This transaction can't be used in subsequent calls to
     * {@link ExpectedValueCheckingStore#acquireLock(StaticBuffer, StaticBuffer, StaticBuffer, StoreTransaction)}
     * .
     * <p>
     * Calling this method at the appropriate time is handled automatically by
     * {@link ExpectedValueCheckingStore}. JanusGraph users don't need to call this
     * method by hand.
     */
    private void mutationStarted() {
        isMutationStarted = true;
    }

    private void lockedOn(ExpectedValueCheckingStore store) {
        final Map<KeyColumn, StaticBuffer> m = expectedValuesByStore.computeIfAbsent(store, k -> new HashMap<>());
    }

    private void checkSingleExpectedValue(KeyColumn kc, StaticBuffer ev, ExpectedValueCheckingStore store) throws BackendException {
        BackendOperation.executeDirect(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                checkSingleExpectedValueUnsafe(kc, ev, store);
                return true;
            }

            @Override
            public String toString() {
                return "ExpectedValueChecking";
            }
        }, maxReadTime);
    }

    private void checkSingleExpectedValueUnsafe(KeyColumn kc, StaticBuffer ev, ExpectedValueCheckingStore store) throws BackendException {
        StaticBuffer nextBuf = BufferUtil.nextBiggerBuffer(kc.getColumn());
        KeySliceQuery ksq = new KeySliceQuery(kc.getKey(), kc.getColumn(), nextBuf);
        // Call getSlice on the wrapped store using the quorum+ consistency tx
        Iterable<Entry> actualEntries = store.getBackingStore().getSlice(ksq, strongConsistentTx);

        if (null == actualEntries)
            actualEntries = ImmutableList.of();

        /*
         * Discard any columns which do not exactly match kc.getColumn().
         *
         * For example, it's possible that the slice returned columns which for
         * which kc.getColumn() is a prefix.
         */
        actualEntries = Iterables.filter(actualEntries, input -> {
            if (!input.getColumn().equals(kc.getColumn())) {
                LOG.debug("Dropping entry {} (only accepting column {})", input, kc.getColumn());
                return false;
            }
            LOG.debug("Accepting entry {}", input);
            return true;
        });

        // Extract values from remaining Entry instances

        Iterable<StaticBuffer> actualValues = Iterables.transform(actualEntries, e -> e.getValueAs(StaticBuffer.STATIC_FACTORY));
        Iterable<StaticBuffer> expectedValues;

        if (null == ev) {
            expectedValues = ImmutableList.of();
        } else {
            expectedValues = ImmutableList.of(ev);
        }

        if (!Iterables.elementsEqual(expectedValues, actualValues)) {
            throw new PermanentLockingException(
                    "Expected value mismatch for " + kc + ": expected="
                            + expectedValues + " vs actual=" + actualValues + " (store=" + store.getName() + ")");
        }
    }

    private void deleteAllLocks() throws BackendException {
        for (ExpectedValueCheckingStore s : expectedValuesByStore.keySet()) {
            s.deleteLocks(this);
        }
    }
}