/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.eventmesh.runtime.core.protocol.http.producer;

import org.apache.eventmesh.api.RequestReplyCallback;
import org.apache.eventmesh.api.SendCallback;
import org.apache.eventmesh.runtime.configuration.EventMeshHTTPConfiguration;
import org.apache.eventmesh.runtime.core.consumergroup.ProducerGroupConf;
import org.apache.eventmesh.runtime.core.plugin.MQProducerWrapper;
import org.apache.eventmesh.runtime.util.EventMeshUtil;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;


import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EventMeshProducer {

    protected AtomicBoolean started = new AtomicBoolean(Boolean.FALSE);

    protected AtomicBoolean inited = new AtomicBoolean(Boolean.FALSE);

    public AtomicBoolean getInited() {
        return inited;
    }

    public AtomicBoolean getStarted() {
        return started;
    }

    protected ProducerGroupConf producerGroupConfig;

    protected EventMeshHTTPConfiguration eventMeshHttpConfiguration;

    public void send(SendMessageContext sendMsgContext, SendCallback sendCallback) throws Exception {
        mqProducerWrapper.send(sendMsgContext.getEvent(), sendCallback);
    }

    public void request(SendMessageContext sendMsgContext, RequestReplyCallback rrCallback, long timeout)
            throws Exception {
        mqProducerWrapper.request(sendMsgContext.getEvent(), rrCallback, timeout);
    }

    public boolean reply(final SendMessageContext sendMsgContext, final SendCallback sendCallback) throws Exception {
        mqProducerWrapper.reply(sendMsgContext.getEvent(), sendCallback);
        return true;
    }

    protected MQProducerWrapper mqProducerWrapper;

    public MQProducerWrapper getMqProducerWrapper() {
        return mqProducerWrapper;
    }

    public synchronized void init(EventMeshHTTPConfiguration eventMeshHttpConfiguration,
                                  ProducerGroupConf producerGroupConfig) throws Exception {
        this.producerGroupConfig = producerGroupConfig;
        this.eventMeshHttpConfiguration = eventMeshHttpConfiguration;

        Properties keyValue = new Properties();
        keyValue.put("producerGroup", producerGroupConfig.getGroupName());
        keyValue.put("instanceName", EventMeshUtil.buildMeshClientID(producerGroupConfig.getGroupName(),
                eventMeshHttpConfiguration.getEventMeshCluster()));

        //TODO for defibus
        keyValue.put("eventMeshIDC", eventMeshHttpConfiguration.getEventMeshIDC());
        mqProducerWrapper = new MQProducerWrapper(eventMeshHttpConfiguration.getEventMeshConnectorPluginType());
        mqProducerWrapper.init(keyValue);
        inited.compareAndSet(false, true);
        log.info("EventMeshProducer [{}] inited.............", producerGroupConfig.getGroupName());
    }


    public synchronized void start() throws Exception {
        if (started.get()) {
            return;
        }

        mqProducerWrapper.start();
        started.compareAndSet(false, true);
        log.info("EventMeshProducer [{}] started.............", producerGroupConfig.getGroupName());
    }

    public synchronized void shutdown() throws Exception {
        if (!inited.get()) {
            return;
        }

        if (!started.get()) {
            return;
        }
        mqProducerWrapper.shutdown();
        inited.compareAndSet(true, false);
        started.compareAndSet(true, false);
        log.info("EventMeshProducer [{}] shutdown.............", producerGroupConfig.getGroupName());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("eventMeshProducer={")
                .append("inited=").append(inited.get()).append(",")
                .append("started=").append(started.get()).append(",")
                .append("producerGroupConfig=").append(producerGroupConfig).append("}");
        return sb.toString();
    }
}
