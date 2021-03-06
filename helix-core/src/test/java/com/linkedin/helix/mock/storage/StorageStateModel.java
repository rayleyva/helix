/**
 * Copyright (C) 2012 LinkedIn Inc <opensource@linkedin.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.helix.mock.storage;

import org.apache.log4j.Logger;

import com.linkedin.helix.NotificationContext;
import com.linkedin.helix.mock.consumer.RelayConfig;
import com.linkedin.helix.mock.consumer.RelayConsumer;
import com.linkedin.helix.model.Message;
import com.linkedin.helix.participant.statemachine.StateModel;

public class StorageStateModel extends StateModel
{

  // private Map<Integer, RelayConsumer> relayConsumersMap;
  private RelayConsumer consumer = null;
  private RelayConfig relayConfig;
  private final StorageAdapter storage;

  private static Logger logger = Logger.getLogger(StorageStateModel.class);

  public StorageStateModel(String stateUnitKey, StorageAdapter storageAdapter)
  {
    // relayConsumersMap = new HashMap<Integer,RelayConsumer>();
    storage = storageAdapter;
    // this.consumerAdapter = consumerAdapter;
  }

  public RelayConfig getRelayConfig()
  {
    return relayConfig;
  }

  public void setRelayConfig(RelayConfig relayConfig)
  {
    this.relayConfig = relayConfig;
  }

  void checkDebug(Message task) throws Exception
  {
    // For debugging purposes
    if (task.getDebug() == true)
    {
      throw new Exception("Exception for debug");
    }
  }

  // @transition(to='to',from='from',blah blah..)
  public void onBecomeSlaveFromOffline(Message task, NotificationContext context)
      throws Exception
  {

    logger.info("Becoming slave from offline");

    checkDebug(task);

    String partition = task.getPartitionName();
    String[] pdata = partition.split("\\.");
    String dbName = pdata[0];

    // Initializations for the storage node to create right tables, indexes
    // etc.
    storage.init(partition);
    storage.setPermissions(partition, "READONLY");

    // start consuming from the relay
    consumer = storage.getNewRelayConsumer(dbName, partition);
    consumer.start();
    // TODO: how do we know we are caught up?

    logger.info("Became slave for partition " + partition);
  }

  // @transition(to='to',from='from',blah blah..)
  public void onBecomeSlaveFromMaster(Message task, NotificationContext context)
      throws Exception
  {

    logger.info("Becoming slave from master");

    checkDebug(task);

    String partition = task.getPartitionName();
    String[] pdata = partition.split("\\.");
    String dbName = pdata[0];
    storage.setPermissions(partition, "READONLY");
    storage.waitForWrites(partition);

    // start consuming from the relay
    consumer = storage.getNewRelayConsumer(dbName, partition);
    consumer.start();

    logger.info("Becamse slave for partition " + partition);
  }

  // @transition(to='to',from='from',blah blah..)
  public void onBecomeMasterFromSlave(Message task, NotificationContext context)
      throws Exception
  {
    logger.info("Becoming master from slave");

    checkDebug(task);

    String partition = task.getPartitionName();

    // stop consumer and refetch from all so all changes are drained
    consumer.flush(); // blocking call

    // TODO: publish the hwm somewhere
    long hwm = consumer.getHwm();
    storage.setHwm(partition, hwm);
    storage.removeConsumer(partition);
    consumer = null;

    // set generation in storage
    Integer generationId = task.getGeneration();
    storage.setGeneration(partition, generationId);

    storage.setPermissions(partition, "READWRITE");

    logger.info("Became master for partition " + partition);
  }

  // @transition(to='to',from='from',blah blah..)
  public void onBecomeOfflineFromSlave(Message task, NotificationContext context)
      throws Exception
  {

    logger.info("Becoming offline from slave");

    checkDebug(task);

    String partition = task.getPartitionName();

    consumer.stop();
    storage.removeConsumer(partition);
    consumer = null;

    storage.setPermissions(partition, "OFFLINE");

    logger.info("Became offline for partition " + partition);
  }
}
