/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.orient.entity;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.orient.entity.EntityAdapter.Resolution;

import com.orientechnologies.orient.core.conflict.ORecordConflictStrategy;
import com.orientechnologies.orient.core.exception.OConcurrentModificationException;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.serialization.serializer.record.ORecordSaveThreadLocal;
import com.orientechnologies.orient.core.storage.OStorage;

import static com.orientechnologies.orient.core.db.record.ORecordOperation.UPDATED;
import static com.orientechnologies.orient.core.record.impl.ODocument.RECORD_TYPE;
import static java.lang.Math.max;
import static java.util.Optional.ofNullable;
import static org.sonatype.nexus.orient.entity.EntityAdapter.Resolution.ALLOW;
import static org.sonatype.nexus.orient.entity.EntityAdapter.Resolution.DENY;

/**
 * {@link ORecordConflictStrategy} that delegates conflict resolution to interested {@link EntityAdapter}s.
 *
 * Invoked when the database detects a potential conflict using multi-version concurrency control (MVCC).
 * It may decide to ALLOW the update if there is no actual conflict, MERGE changes, or DENY the update if
 * the conflict exists and cannot be resolved by merging.
 *
 * @since 3.next
 */
@Named
@Singleton
public class ConflictHook
    extends ComponentSupport
    implements ORecordConflictStrategy
{
  public static final String NAME = "ConflictHook";

  private final Map<String, EntityAdapter<?>> resolvingAdapters = new ConcurrentHashMap<>();

  private final Map<String, String> typeNamesByClusterKey = new ConcurrentHashMap<>();

  @Override
  public String getName() {
    return NAME;
  }

  /**
   * Enables conflict resolution for the given {@link EntityAdapter}.
   */
  public void enableConflictResolution(final EntityAdapter<?> adapter) {
    log.trace("Enable conflict resolution for {}", adapter);
    resolvingAdapters.put(adapter.getTypeName(), adapter);
  }

  /**
   * Disables conflict resolution for the given {@link EntityAdapter}.
   */
  public void disableConflictResolution(final EntityAdapter<?> adapter) {
    log.trace("Disable conflict resolution for {}", adapter);
    resolvingAdapters.remove(adapter.getTypeName());
  }

  /**
   * Attempts to resolve the potential conflict by delegating to the resolving entity adapter.
   */
  @Override
  @Nullable
  public byte[] onUpdate(final OStorage storage,
                         final byte recordType,
                         final ORecordId rid,
                         final int recordVersion,
                         final byte[] content,
                         final AtomicInteger dbVersion)
  {
    // most records won't have an entity adapter interested in resolving their conflicts
    Optional<EntityAdapter<?>> adapter = findResolvingAdapter(storage, rid.getClusterId());
    if (adapter.isPresent()) {

      // attempt to load the current stored record content
      byte[] storedContent = storage.readRecord(rid, null, false, false, null).getResult().getBuffer();

      Resolution resolution;
      ODocument changeRecord = null;

      if (recordType == RECORD_TYPE) {
        // turn the stored content into a proper record
        ODocument storedRecord = new ODocument(rid).fromStream(storedContent);

        // retrieve the change we originally wanted to save
        changeRecord = getChangeRecord(rid, content);

        // delegate conflict resolution to owning entity adapter
        resolution = adapter.get().resolve(storedRecord, changeRecord);

        log.trace("{} update of {} with {}", resolution, storedRecord, changeRecord);
      }
      else {
        // binary content - no merging, we can only do a simple comparison
        resolution = Arrays.equals(storedContent, content) ? ALLOW : DENY;
      }

      switch (resolution) {
        case ALLOW:
          dbVersion.set(max(dbVersion.get(), recordVersion));
          return null; // this tells Orient to store the original change
        case MERGE:
          // when merging we need to give the DB version an extra bump
          dbVersion.set(max(dbVersion.get(), recordVersion) + 1);
          return ofNullable(changeRecord).map(ODocument::toStream).orElse(null);
        default:
          break;
      }
    }

    throw new OConcurrentModificationException(rid, dbVersion.get(), recordVersion, UPDATED);
  }

  /**
   * Returns a record containing the original in-conflict changes.
   */
  private ODocument getChangeRecord(final ORecordId rid, final byte[] content) {
    // check the 'record-save' cache as it's likely already there
    ODocument record = (ODocument) ORecordSaveThreadLocal.getLast();
    if (record == null || !rid.equals(record.getIdentity())) {
      record = new ODocument(rid).fromStream(content);
    }
    return record;
  }

  /**
   * Returns an optional {@link EntityAdapter} that 'owns' the clusterId and is interested in resolving its conflicts.
   */
  private Optional<EntityAdapter<?>> findResolvingAdapter(final OStorage storage, final int clusterId) {
    String clusterKey = storage.getName() + '#' + clusterId; // clusterIds are not unique across DBs
    String typeName = typeNamesByClusterKey.computeIfAbsent(clusterKey,
        k -> storage.getPhysicalClusterNameById(clusterId));

    return ofNullable(typeName).map(resolvingAdapters::get);
  }
}