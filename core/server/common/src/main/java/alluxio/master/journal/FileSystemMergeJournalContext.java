/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master.journal;

import alluxio.conf.Configuration;
import alluxio.conf.PropertyKey;
import alluxio.exception.status.UnavailableException;
import alluxio.proto.journal.Journal.JournalEntry;

import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Context for merging journal entries together for a wrapped journal context.
 *
 * This is used so that we can combine several journal entries into one using a merge
 * function. This helps us resolve the inconsistency between primary and standby and
 * decrease the chance for a standby to see intermediate state of a file system operation.
 *
 * This journal context is made thread-safe because metadata sync creates worker threads to fetch
 * metadata and reuses the same journal context.
 */
@ThreadSafe
public class FileSystemMergeJournalContext implements JournalContext {
  // It will log a warning if the number of buffered journal entries exceed 100
  private static final int MAX_LOGGING_ENTRIES
      = Configuration.getInt(
          PropertyKey.MASTER_MERGE_JOURNAL_CONTEXT_NUM_ENTRIES_LOGGING_THRESHOLD);

  private static final Logger LOG = LoggerFactory.getLogger(FileSystemMergeJournalContext.class);

  protected final JournalContext mJournalContext;
  protected final JournalEntryMerger mJournalEntryMerger;

  /**
   * Constructs a {@link FileSystemMergeJournalContext}.
   * @param journalContext the journal context to wrap
   * @param journalEntryMerger the merger which merges multiple journal entries into one
   */
  public FileSystemMergeJournalContext(JournalContext journalContext,
                             JournalEntryMerger journalEntryMerger) {
    Preconditions.checkNotNull(journalContext, "journalContext is null");
    Preconditions.checkNotNull(journalEntryMerger, "mergeOperator is null");
    mJournalContext = journalContext;
    mJournalEntryMerger = journalEntryMerger;
  }

  /**
   * Adds the new journal entry into the journal entry merger.
   * If the size of the outstanding journal entries are bigger than the threshold,
   * we will flush them immediately into the async journal writer.
   * This is used to prevent a recursive file system operation adding
   * too many journal entries without being flushed and taking huge memory.
   * Though it might create inconsistency between primary and standby if
   * the primary crashes between two flushes.
   * Tune PropertyKey.MASTER_MERGE_JOURNAL_CONTEXT_MAX_ENTRIES to trade off
   * @param entry the {@link JournalEntry} to append to the journal
   */
  @Override
  public synchronized void append(JournalEntry entry) {
    mJournalEntryMerger.add(entry);
    List<JournalEntry> journalEntries = mJournalEntryMerger.getMergedJournalEntries();
    if (journalEntries.size() >= MAX_LOGGING_ENTRIES) {
      LOG.warn("MergeJournalContext has " + journalEntries.size()
          + " entries, over the limit of " + MAX_LOGGING_ENTRIES
          + ", forcefully merging journal entries and add them to the async journal writer"
          + "\n Journal Entry: \n"
          + entry, new Exception("MergeJournalContext Stacktrace:"));
      appendMergedJournals();
    }
  }

  @Override
  public synchronized void close() throws UnavailableException {
    try {
      appendMergedJournals();
    } finally {
      mJournalContext.close();
    }
  }

  /**
   * Merges all journals and then flushes them.
   * The journal writer will commit these journals synchronously.
   */
  @Override
  public synchronized void flush() throws UnavailableException {
    // Skip flushing the journal if no journal entries to append
    if (appendMergedJournals()) {
      mJournalContext.flush();
    }
  }

  protected synchronized boolean appendMergedJournals() {
    List<JournalEntry> journalEntries = mJournalEntryMerger.getMergedJournalEntries();
    if (journalEntries.size() == 0) {
      return false;
    }

    journalEntries.forEach(mJournalContext::append);
    mJournalEntryMerger.clear();
    return true;
  }

  /**
   * @return the underlying journal context
   */
  public JournalContext getUnderlyingJournalContext() {
    return mJournalContext;
  }
}
