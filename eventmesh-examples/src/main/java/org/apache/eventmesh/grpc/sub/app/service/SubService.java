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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.eventmesh.grpc.sub.app.service;

import static org.apache.eventmesh.common.ExampleConstants.ENV;
import static org.apache.eventmesh.common.ExampleConstants.IDC;
import static org.apache.eventmesh.common.ExampleConstants.SERVER_PORT;
import static org.apache.eventmesh.common.ExampleConstants.SUB_SYS;

import org.apache.eventmesh.client.grpc.config.EventMeshGrpcClientConfig;
import org.apache.eventmesh.client.grpc.consumer.EventMeshGrpcConsumer;
import org.apache.eventmesh.common.ExampleConstants;
import org.apache.eventmesh.common.protocol.SubscriptionItem;
import org.apache.eventmesh.common.protocol.SubscriptionMode;
import org.apache.eventmesh.common.protocol.SubscriptionType;
import org.apache.eventmesh.common.utils.IPUtils;
import org.apache.eventmesh.grpc.pub.eventmeshmessage.AsyncPublishInstance;
import org.apache.eventmesh.util.Utils;

import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

import javax.annotation.PreDestroy;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SubService implements InitializingBean {

    private EventMeshGrpcConsumer eventMeshGrpcConsumer;

    private Properties properties;

    private final SubscriptionItem subscriptionItem = new SubscriptionItem();

    private final String localIp = IPUtils.getLocalAddress();
    private final String localPort = properties.getProperty(SERVER_PORT);
    private final String eventMeshIp = properties.getProperty(ExampleConstants.EVENTMESH_IP);
    private final String eventMeshGrpcPort = properties.getProperty(ExampleConstants.EVENTMESH_GRPC_PORT);
    private final String url = "http://" + localIp + ":" + localPort + "/sub/test";

    // CountDownLatch size is the same as messageSize in AsyncPublishInstance.java (Publisher)
    private final CountDownLatch countDownLatch = new CountDownLatch(AsyncPublishInstance.MESSAGE_SIZE);

    @Override
    public void afterPropertiesSet() throws Exception {

        final EventMeshGrpcClientConfig eventMeshClientConfig = EventMeshGrpcClientConfig.builder()
                .serverAddr(eventMeshIp)
                .serverPort(Integer.parseInt(eventMeshGrpcPort))
                .consumerGroup(ExampleConstants.DEFAULT_EVENTMESH_TEST_CONSUMER_GROUP)
                .env(ENV).idc(IDC)
                .sys(SUB_SYS)
                .build();

        eventMeshGrpcConsumer = new EventMeshGrpcConsumer(eventMeshClientConfig);
        eventMeshGrpcConsumer.init();

        subscriptionItem.setTopic(ExampleConstants.EVENTMESH_GRPC_ASYNC_TEST_TOPIC);
        subscriptionItem.setMode(SubscriptionMode.CLUSTERING);
        subscriptionItem.setType(SubscriptionType.ASYNC);

        eventMeshGrpcConsumer.subscribe(Collections.singletonList(subscriptionItem), url);

        properties = Utils.readPropertiesFile(ExampleConstants.CONFIG_FILE_NAME);


        // Wait for all messaged to be consumed
        final Thread stopThread = new Thread(() -> {
            try {
                countDownLatch.await();
            } catch (InterruptedException e) {
                if (log.isWarnEnabled()) {
                    log.warn("exception occurred when countDownLatch.await ", e);
                }
            }

            if (log.isInfoEnabled()) {
                log.info("stopThread start....");
            }

            //throw new RuntimeException();
        });

        stopThread.start();
    }

    @PreDestroy
    public void cleanup() {
        if (log.isInfoEnabled()) {
            log.info("start destory ....");
        }

        try {
            eventMeshGrpcConsumer.unsubscribe(Collections.singletonList(subscriptionItem), url);
        } catch (Exception e) {
            log.error("exception occurred when unsubscribe ", e);
        }
        try (EventMeshGrpcConsumer ignore = eventMeshGrpcConsumer) {
            // close consumer
        } catch (Exception e) {
            log.error("exception occurred when close consumer ", e);
        }

        if (log.isInfoEnabled()) {
            log.info("end destory.");
        }
    }

    /**
     * Count the message already consumed
     */
    public void consumeMessage(final String msg) {
        if (log.isInfoEnabled()) {
            log.info("consume message: {}", msg);
        }
        countDownLatch.countDown();
        if (log.isInfoEnabled()) {
            log.info("remaining number of messages to be consumed: {}", countDownLatch.getCount());
        }
    }
}
