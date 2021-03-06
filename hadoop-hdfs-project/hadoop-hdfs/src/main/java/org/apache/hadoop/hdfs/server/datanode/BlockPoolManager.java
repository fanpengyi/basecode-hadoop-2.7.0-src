/**
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
package org.apache.hadoop.hdfs.server.datanode;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.logging.Log;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.security.UserGroupInformation;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.PrivilegedExceptionAction;
import java.util.*;

/**
 * Manages the BPOfferService objects for the data node.
 * Creation, removal, starting, stopping, shutdown on BPOfferService
 * objects must be done via APIs in this class.
 */
@InterfaceAudience.Private
class BlockPoolManager {
  private static final Log LOG = DataNode.LOG;
  
  private final Map<String, BPOfferService> bpByNameserviceId =
    Maps.newHashMap();
  private final Map<String, BPOfferService> bpByBlockPoolId =
    Maps.newHashMap();
  private final List<BPOfferService> offerServices =
    Lists.newArrayList();

  private final DataNode dn;

  //This lock is used only to ensure exclusion of refreshNamenodes
  private final Object refreshNamenodesLock = new Object();
  
  BlockPoolManager(DataNode dn) {
    this.dn = dn;
  }
  
  synchronized void addBlockPool(BPOfferService bpos) {
    Preconditions.checkArgument(offerServices.contains(bpos),
        "Unknown BPOS: %s", bpos);
    if (bpos.getBlockPoolId() == null) {
      throw new IllegalArgumentException("Null blockpool id");
    }
    bpByBlockPoolId.put(bpos.getBlockPoolId(), bpos);
  }
  
  /**
   * Returns the array of BPOfferService objects. 
   * Caution: The BPOfferService returned could be shutdown any time.
   */
  synchronized BPOfferService[] getAllNamenodeThreads() {
    BPOfferService[] bposArray = new BPOfferService[offerServices.size()];
    return offerServices.toArray(bposArray);
  }
      
  synchronized BPOfferService get(String bpid) {
    return bpByBlockPoolId.get(bpid);
  }
  
  synchronized void remove(BPOfferService t) {
    offerServices.remove(t);
    if (t.hasBlockPoolId()) {
      // It's possible that the block pool never successfully registered
      // with any NN, so it was never added it to this map
      bpByBlockPoolId.remove(t.getBlockPoolId());
    }
    
    boolean removed = false;
    for (Iterator<BPOfferService> it = bpByNameserviceId.values().iterator();
         it.hasNext() && !removed;) {
      BPOfferService bpos = it.next();
      if (bpos == t) {
        it.remove();
        LOG.info("Removed " + bpos);
        removed = true;
      }
    }
    
    if (!removed) {
      LOG.warn("Couldn't remove BPOS " + t + " from bpByNameserviceId map");
    }
  }
  
  void shutDownAll(BPOfferService[] bposArray) throws InterruptedException {
    if (bposArray != null) {
      for (BPOfferService bpos : bposArray) {
        bpos.stop(); //interrupts the threads
      }
      //now join
      for (BPOfferService bpos : bposArray) {
        bpos.join();
      }
    }
  }
  
  synchronized void startAll() throws IOException {
    try {
      UserGroupInformation.getLoginUser().doAs(
          new PrivilegedExceptionAction<Object>() {
            @Override
            public Object run() throws Exception {
              //TODO 遍历所有的 BOPOfferService 遍历所有的联邦
              for (BPOfferService bpos : offerServices) {
                // TODO ***重要
                bpos.start();
              }
              return null;
            }
          });
    } catch (InterruptedException ex) {
      IOException ioe = new IOException();
      ioe.initCause(ex.getCause());
      throw ioe;
    }
  }
  
  void joinAll() {
    for (BPOfferService bpos: this.getAllNamenodeThreads()) {
      bpos.join();
    }
  }
  
  void refreshNamenodes(Configuration conf)
      throws IOException {
    LOG.info("Refresh request received for nameservices: " + conf.get
            (DFSConfigKeys.DFS_NAMESERVICES));

    Map<String, Map<String, InetSocketAddress>> newAddressMap = DFSUtil
            .getNNServiceRpcAddressesForCluster(conf);

    synchronized (refreshNamenodesLock) {
      //TODO 重要代码
      doRefreshNamenodes(newAddressMap);
    }
  }

  /**
   * 进行 datanode 的注册 和心跳
   *
   * @param addrMap
   * @throws IOException
   */
  private void doRefreshNamenodes(
      Map<String, Map<String, InetSocketAddress>> addrMap) throws IOException {
    assert Thread.holdsLock(refreshNamenodesLock);

    Set<String> toRefresh = Sets.newLinkedHashSet();
    Set<String> toAdd = Sets.newLinkedHashSet();
    Set<String> toRemove;
    
    synchronized (this) {
      // Step 1. For each of the new nameservices, figure out whether
      // it's an update of the set of NNs for an existing NS,
      // or an entirely new nameservice.

      //TODO 通常情况下 HDFS集群是 HA 架构 只会有一个 nameservice(hadoop active | hadoop standby)

      /**
       * 如果是联邦架构 里面会有多个 nameservice
       * hadoop1,hadoop2 -> 联邦1 service1
       * hadoop3,hadoop4 -> 联邦1 service2
       */
      for (String nameserviceId : addrMap.keySet()) {
        if (bpByNameserviceId.containsKey(nameserviceId)) {
          toRefresh.add(nameserviceId);
        } else {
          //TODO toadd 里面有多少id 就有多少个联邦，一个联邦就是一个 nameservice
          toAdd.add(nameserviceId);
        }
      }
      
      // Step 2. Any nameservices we currently have but are no longer present
      // need to be removed.
      toRemove = Sets.newHashSet(Sets.difference(
          bpByNameserviceId.keySet(), addrMap.keySet()));
      
      assert toRefresh.size() + toAdd.size() ==
        addrMap.size() :
          "toAdd: " + Joiner.on(",").useForNull("<default>").join(toAdd) +
          "  toRemove: " + Joiner.on(",").useForNull("<default>").join(toRemove) +
          "  toRefresh: " + Joiner.on(",").useForNull("<default>").join(toRefresh);

      
      // Step 3. Start new nameservices
      if (!toAdd.isEmpty()) {
        LOG.info("Starting BPOfferServices for nameservices: " +
            Joiner.on(",").useForNull("<default>").join(toAdd));
        //TODO 遍历所有的联邦 一个联邦里面有两个 NameNode(HA)

        //如果是2个联邦 那么这个地方就会有两个值
        //BPOfferservice -> 一个联邦  有两个联邦 执行两次

        //TODO toAdd 里是 nameserviceId 有几个nameserviceId 就会创建几个 BPOfferService
        for (String nsToAdd : toAdd) {
          ArrayList<InetSocketAddress> addrs =
                  //如果有两个高可用
            Lists.newArrayList(addrMap.get(nsToAdd).values());
          //TODO 重要的关系
          /**
           * 一个联邦对应一个 BPOfferService
           * 一个联邦里面有两个 NameNode (HA) ,一个NameNode 就是一个 BpserviceActor
           * 就是说 一个 BPOfferService 对应了两个 BPServiceActor
           */
          //
          BPOfferService bpos = createBPOS(addrs);

          bpByNameserviceId.put(nsToAdd, bpos);
          offerServices.add(bpos);
        }
      }
      //TODO DataNode 向 NameNode 进行注册和心跳
      startAll();
    }

    // Step 4. Shut down old nameservices. This happens outside
    // of the synchronized(this) lock since they need to call
    // back to .remove() from another thread
    if (!toRemove.isEmpty()) {
      LOG.info("Stopping BPOfferServices for nameservices: " +
          Joiner.on(",").useForNull("<default>").join(toRemove));
      
      for (String nsToRemove : toRemove) {
        BPOfferService bpos = bpByNameserviceId.get(nsToRemove);
        bpos.stop();
        bpos.join();
        // they will call remove on their own
      }
    }
    
    // Step 5. Update nameservices whose NN list has changed
    if (!toRefresh.isEmpty()) {
      LOG.info("Refreshing list of NNs for nameservices: " +
          Joiner.on(",").useForNull("<default>").join(toRefresh));
      
      for (String nsToRefresh : toRefresh) {
        BPOfferService bpos = bpByNameserviceId.get(nsToRefresh);
        ArrayList<InetSocketAddress> addrs =
          Lists.newArrayList(addrMap.get(nsToRefresh).values());
        bpos.refreshNNList(addrs);
      }
    }
  }

  /**
   * Extracted out for test purposes.
   */
  protected BPOfferService createBPOS(List<InetSocketAddress> nnAddrs) {

    return new BPOfferService(nnAddrs, dn);
  }
}
