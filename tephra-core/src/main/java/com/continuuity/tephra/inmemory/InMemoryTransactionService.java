/*
 * Copyright 2012-2014 Continuuity, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.continuuity.tephra.inmemory;

import com.continuuity.tephra.TransactionManager;
import com.continuuity.tephra.TxConstants;
import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;
import com.google.inject.Provider;
import org.apache.hadoop.conf.Configuration;
import org.apache.twill.common.Cancellable;
import org.apache.twill.discovery.Discoverable;
import org.apache.twill.discovery.DiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * Transaction server that manages transaction data for the Reactor.
 * <p>
 *   Transaction server is HA, one can start multiple instances, only one of which is active and will register itself in
 *   discovery service.
 * </p>
 */
public class InMemoryTransactionService extends AbstractService {
  private static final Logger LOG = LoggerFactory.getLogger(InMemoryTransactionService.class);

  private final DiscoveryService discoveryService;
  private final String serviceName;
  protected final Provider<TransactionManager> txManagerProvider;
  private Cancellable cancelDiscovery;
  protected TransactionManager txManager;

  // thrift server config
  protected final String address;
  protected final int threads;
  protected final int ioThreads;
  protected final int maxReadBufferBytes;

  @Inject
  public InMemoryTransactionService(Configuration conf,
                            DiscoveryService discoveryService,
                            Provider<TransactionManager> txManagerProvider) {

    this.discoveryService = discoveryService;
    this.txManagerProvider = txManagerProvider;
    this.serviceName = conf.get(TxConstants.Service.CFG_DATA_TX_DISCOVERY_SERVICE_NAME,
                                TxConstants.Service.DEFAULT_DATA_TX_DISCOVERY_SERVICE_NAME);

    address = conf.get(TxConstants.Service.CFG_DATA_TX_BIND_ADDRESS, TxConstants.Service.DEFAULT_DATA_TX_BIND_ADDRESS);

    // Retrieve the number of threads for the service
    threads = conf.getInt(TxConstants.Service.CFG_DATA_TX_SERVER_THREADS,
                          TxConstants.Service.DEFAULT_DATA_TX_SERVER_THREADS);
    ioThreads = conf.getInt(TxConstants.Service.CFG_DATA_TX_SERVER_IO_THREADS,
                            TxConstants.Service.DEFAULT_DATA_TX_SERVER_IO_THREADS);

    maxReadBufferBytes = conf.getInt(TxConstants.Service.CFG_DATA_TX_THRIFT_MAX_READ_BUFFER,
                                     TxConstants.Service.DEFAULT_DATA_TX_THRIFT_MAX_READ_BUFFER);

    LOG.info("Configuring TransactionService" +
               ", address: " + address +
               ", threads: " + threads +
               ", io threads: " + ioThreads +
               ", max read buffer (bytes): " + maxReadBufferBytes);
  }

  protected void undoRegiser() {
    if (cancelDiscovery != null) {
      cancelDiscovery.cancel();
    }
  }

  protected void doRegister() {
    cancelDiscovery = discoveryService.register(new Discoverable() {
      @Override
      public String getName() {
        return serviceName;
      }

      @Override
      public InetSocketAddress getSocketAddress() {
        return getAddress();
      }
    });
  }

  protected InetSocketAddress getAddress() {
    return new InetSocketAddress(1);
  }

  @Override
  protected void doStart() {
    try {
      txManager = txManagerProvider.get();
      txManager.startAndWait();
      doRegister();
      LOG.info("Transaction Thrift service started successfully on " + getAddress());
      notifyStarted();
    } catch (Throwable t) {
      LOG.info("Transaction Thrift service didn't start on " + getAddress());
      notifyFailed(t);
    }
  }

  @Override
  protected void doStop() {
    undoRegiser();
    txManager.stopAndWait();
    notifyStopped();
  }

}
