/**
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */
package org.opencastproject.assetmanager.impl;

import static java.lang.String.format;
import static org.opencastproject.assetmanager.api.fn.Enrichments.enrich;

import org.opencastproject.assetmanager.api.AssetManager;
import org.opencastproject.assetmanager.api.Snapshot;
import org.opencastproject.assetmanager.api.fn.Snapshots;
import org.opencastproject.assetmanager.api.query.ADeleteQuery;
import org.opencastproject.assetmanager.api.query.AQueryBuilder;
import org.opencastproject.assetmanager.api.query.RichAResult;
import org.opencastproject.assetmanager.api.query.Target;
import org.opencastproject.assetmanager.impl.query.AbstractADeleteQuery.DeleteSnapshotHandler;
import org.opencastproject.index.IndexProducer;
import org.opencastproject.mediapackage.MediaPackage;
import org.opencastproject.message.broker.api.MessageReceiver;
import org.opencastproject.message.broker.api.MessageSender;
import org.opencastproject.message.broker.api.MessageSender.DestinationType;
import org.opencastproject.message.broker.api.assetmanager.AssetManagerItem;
import org.opencastproject.message.broker.api.assetmanager.AssetManagerItem.TakeSnapshot;
import org.opencastproject.message.broker.api.index.AbstractIndexProducer;
import org.opencastproject.message.broker.api.index.IndexRecreateObject;
import org.opencastproject.message.broker.api.index.IndexRecreateObject.Service;
import org.opencastproject.security.api.AccessControlList;
import org.opencastproject.security.api.AuthorizationService;
import org.opencastproject.security.api.DefaultOrganization;
import org.opencastproject.security.api.Organization;
import org.opencastproject.security.api.OrganizationDirectoryService;
import org.opencastproject.security.api.SecurityService;
import org.opencastproject.security.util.SecurityUtil;
import org.opencastproject.util.data.Effect0;
import org.opencastproject.workspace.api.Workspace;

import com.entwinemedia.fn.P1Lazy;

import org.apache.commons.lang3.text.WordUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Bind an asset manager to ActiveMQ messaging.
 * <p>
 * Please make sure to {@link #close()} the AssetManager.
 */
public class AssetManagerWithMessaging extends AssetManagerDecorator
        implements DeleteSnapshotHandler, IndexProducer, AutoCloseable {
  /** Log facility */
  private static final Logger logger = LoggerFactory.getLogger(AssetManagerWithMessaging.class);

  private final MessageSender messageSender;
  private final MessageReceiver messageReceiver;
  private final AuthorizationService authSvc;
  private final OrganizationDirectoryService orgDir;
  private final SecurityService secSvc;
  private final Workspace workspace;

  private final AbstractIndexProducer indexProducerMsgReceiver;

  public AssetManagerWithMessaging(final AssetManager delegate, final MessageSender messageSender,
          MessageReceiver messageReceiver, AuthorizationService authSvc, OrganizationDirectoryService orgDir,
          SecurityService secSvc, Workspace workspace, final String systemUserName) {
    super(delegate);
    this.messageSender = messageSender;
    this.messageReceiver = messageReceiver;
    this.authSvc = authSvc;
    this.orgDir = orgDir;
    this.secSvc = secSvc;
    this.workspace = workspace;
    this.indexProducerMsgReceiver = new AbstractIndexProducer() {
      @Override
      public String getClassName() {
        return AssetManagerWithMessaging.class.getName();
      }

      @Override
      public MessageReceiver getMessageReceiver() {
        return AssetManagerWithMessaging.this.messageReceiver;
      }

      @Override
      public Service getService() {
        return Service.AssetManager;
      }

      @Override
      public MessageSender getMessageSender() {
        return AssetManagerWithMessaging.this.messageSender;
      }

      @Override
      public SecurityService getSecurityService() {
        return AssetManagerWithMessaging.this.secSvc;
      }

      @Override
      public String getSystemUserName() {
        return systemUserName;
      }

      @Override
      public void repopulate(final String indexName) throws Exception {
        final AQueryBuilder q = delegate.createQuery();
        final RichAResult r = enrich(q.select(q.snapshot()).where(q.version().isLatest()).run());
        logger.info(format("Populating index '%s' with %d snapshots | start", indexName, r.getSize()));
        final Map<String, List<Snapshot>> byOrg = r.getSnapshots().groupMulti(Snapshots.getOrganizationId);
        final IndexRecreationBatch batch = mkRecreationBatch(indexName, AssetManagerItem.ASSETMANAGER_QUEUE_PREFIX,
                (int) r.getSize());
        for (final Map.Entry<String, List<Snapshot>> es : byOrg.entrySet()) {
          final Organization organization = AssetManagerWithMessaging.this.orgDir.getOrganization(es.getKey());
          for (final Snapshot e : es.getValue()) {
            batch.update(organization, new P1Lazy<Serializable>() {
              @Override
              public Serializable get1() {
                return mkTakeSnapshotMessage(e);
              }
            });
          }
        }
        logger.info("Populating index | end");
        Organization organization = new DefaultOrganization();
        SecurityUtil.runAs(getSecurityService(), organization,
                SecurityUtil.createSystemUser(getSystemUserName(), organization), new Effect0() {
                  @Override
                  protected void run() {
                    String destinationId = AssetManagerItem.ASSETMANAGER_QUEUE_PREFIX + WordUtils.capitalize(indexName);
                    messageSender.sendObjectMessage(destinationId, MessageSender.DestinationType.Queue,
                            IndexRecreateObject.end(indexName, IndexRecreateObject.Service.AssetManager));
                  }
                });
      }
    };
    this.indexProducerMsgReceiver.activate();
  }

  @Override
  public void close() throws Exception {
    indexProducerMsgReceiver.deactivate();
  }

  @Override
  public Snapshot takeSnapshot(String owner, MediaPackage mp) {
    final Snapshot snapshot = super.takeSnapshot(owner, mp);
    notifyTakeSnapshot(snapshot);
    return snapshot;
  }

  @Override
  public AQueryBuilder createQuery() {
    return new AQueryBuilderDecorator(super.createQuery()) {
      @Override
      public ADeleteQuery delete(String owner, Target target) {
        return new ADeleteQueryWithMessaging(super.delete(owner, target));
      }
    };
  }

  public void notifyTakeSnapshot(Snapshot snapshot) {
    logger.info(format("Send update message for snapshot %s, %s to ActiveMQ",
            snapshot.getMediaPackage().getIdentifier().toString(), snapshot.getVersion()));
    messageSender.sendObjectMessage(AssetManagerItem.ASSETMANAGER_QUEUE, DestinationType.Queue,
            mkTakeSnapshotMessage(snapshot));
  }

  @Override
  public void notifyDeleteSnapshot(String mpId, VersionImpl version) {
    logger.info(format("Send delete message for snapshot %s, %s to ActiveMQ", mpId, version));
    messageSender.sendObjectMessage(AssetManagerItem.ASSETMANAGER_QUEUE, DestinationType.Queue,
            AssetManagerItem.deleteSnapshot(mpId, version.value(), new Date()));
  }

  @Override
  public void notifyDeleteEpisode(String mpId) {
    logger.info(format("Send delete message for episode %s to ActiveMQ", mpId));
    messageSender.sendObjectMessage(AssetManagerItem.ASSETMANAGER_QUEUE, DestinationType.Queue,
            AssetManagerItem.deleteEpisode(mpId, new Date()));
  }

  /**
   * Create a {@link TakeSnapshot} message.
   * <p>
   * Do not call outside of a security context.
   */
  private TakeSnapshot mkTakeSnapshotMessage(Snapshot snapshot) {
    final AccessControlList acl = authSvc.getActiveAcl(snapshot.getMediaPackage()).getA();
    return AssetManagerItem.add(workspace, snapshot.getMediaPackage(), acl, getVersionLong(snapshot),
            snapshot.getArchivalDate());
  }

  private long getVersionLong(Snapshot snapshot) {
    try {
      return Long.parseLong(snapshot.getVersion().toString());
    } catch (NumberFormatException e) {
      // The index requires a version to be a long value.
      // Since the asset manager default implementation uses long values that should be not a problem.
      // However, a decent exception message is helpful if a different implementation of the asset manager
      // is used.
      throw new RuntimeException("The current implementation of the index requires versions being of type 'long'.");
    }
  }

  /*
   * ------------------------------------------------------------------------------------------------------------------
   */

  @Override
  public void repopulate(String indexName) throws Exception {
    indexProducerMsgReceiver.repopulate(indexName);
  }

  /*
   * ------------------------------------------------------------------------------------------------------------------
   */

  /**
   * Call {@link org.opencastproject.assetmanager.impl.query.AbstractADeleteQuery#run(DeleteSnapshotHandler)} with a
   * delete handler that sends messages to ActiveMQ. Also make sure to propagate the behaviour to subsequent instances.
   */
  private final class ADeleteQueryWithMessaging extends ADeleteQueryDecorator {
    ADeleteQueryWithMessaging(ADeleteQuery delegate) {
      super(delegate);
    }

    @Override
    public long run() {
      return RuntimeTypes.convert(delegate).run(AssetManagerWithMessaging.this);
    }

    @Override
    protected ADeleteQueryDecorator mkDecorator(ADeleteQuery delegate) {
      return new ADeleteQueryWithMessaging(delegate);
    }
  }
}
