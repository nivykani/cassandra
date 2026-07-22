/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db;

import java.util.Set;

import org.junit.Test;

import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.db.compaction.OperationType;
import org.apache.cassandra.db.lifecycle.LifecycleTransaction;
import org.apache.cassandra.io.sstable.format.SSTableReader;

import static org.junit.Assert.assertFalse;

/**
 * Reproduces a production bug where truncateBlocking() silently succeeds without
 * actually truncating data when runWithCompactionsDisabled() returns null.
 */
public class TruncateSilentTest extends CQLTester
{
    @Test
    public void testTruncateSilentlyFailsWhenCompactionsCannotBeDisabled() throws Throwable
    {
        createTable("CREATE TABLE %s (id int PRIMARY KEY, v text)");

        execute("INSERT INTO %s (id, v) VALUES (1, 'a')");
        execute("INSERT INTO %s (id, v) VALUES (2, 'b')");
        execute("INSERT INTO %s (id, v) VALUES (3, 'c')");
        flush();

        ColumnFamilyStore cfs = getCurrentColumnFamilyStore();
        Set<SSTableReader> sstablesBefore = cfs.getLiveSSTables();
        assertFalse("Should have SSTables after flush", sstablesBefore.isEmpty());

        // Hold a transaction open on the live SSTables without a real compaction task
        // behind it: this marks them "compacting" in the Tracker but registers nothing
        // in CompactionManager, so interruptCompactionForCFs has nothing to stop and
        // waitForCessation runs out its full 1-minute timeout.
        LifecycleTransaction txn = cfs.getTracker().tryModify(sstablesBefore, OperationType.COMPACTION);
        try
        {
            // This should fail or throw, but due to the bug it silently returns
            // without truncating and logs "Truncate of ks.table is complete"
            cfs.truncateBlocking();

            // BUG: data survives truncation
            assertRows(execute("SELECT * FROM %s WHERE id = 1"), row(1, "a"));
            assertRows(execute("SELECT * FROM %s WHERE id = 2"), row(2, "b"));
            assertRows(execute("SELECT * FROM %s WHERE id = 3"), row(3, "c"));

            // BUG: SSTables were never discarded
            assertFalse("SSTables should still be present after silent truncation failure",
                    cfs.getLiveSSTables().isEmpty());
        }
        finally
        {
            txn.abort();
        }
    }
}
