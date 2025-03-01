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

package org.apache.eventmesh.client.grpc.util;

import org.apache.eventmesh.client.grpc.config.EventMeshGrpcClientConfig;
import org.apache.eventmesh.client.tcp.common.EventMeshCommon;
import org.apache.eventmesh.common.Constants;
import org.apache.eventmesh.common.EventMeshMessage;
import org.apache.eventmesh.common.protocol.grpc.common.ProtocolKey;
import org.apache.eventmesh.common.protocol.grpc.protos.BatchMessage;
import org.apache.eventmesh.common.protocol.grpc.protos.RequestHeader;
import org.apache.eventmesh.common.protocol.grpc.protos.SimpleMessage;
import org.apache.eventmesh.common.utils.IPUtils;
import org.apache.eventmesh.common.utils.JsonUtils;
import org.apache.eventmesh.common.utils.RandomStringUtils;
import org.apache.eventmesh.common.utils.ThreadUtils;

import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.cloudevents.CloudEvent;
import io.cloudevents.SpecVersion;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.jackson.JsonFormat;

import com.fasterxml.jackson.core.type.TypeReference;

public class EventMeshClientUtil {

    public static RequestHeader buildHeader(EventMeshGrpcClientConfig clientConfig, String protocolType) {
        return RequestHeader.newBuilder()
                .setEnv(clientConfig.getEnv())
                .setIdc(clientConfig.getIdc())
                .setIp(IPUtils.getLocalAddress())
                .setPid(Long.toString(ThreadUtils.getPID()))
                .setSys(clientConfig.getSys())
                .setLanguage(clientConfig.getLanguage())
                .setUsername(clientConfig.getUserName())
                .setPassword(clientConfig.getPassword())
                .setProtocolType(protocolType)
                .setProtocolDesc(Constants.PROTOCOL_GRPC)
                // default CloudEvents version is V1
                .setProtocolVersion(SpecVersion.V1.toString())
                .build();
    }

    @SuppressWarnings("unchecked")
    public static <T> T buildMessage(final SimpleMessage message, final String protocolType) {
        final String seq = message.getSeqNum();
        final String uniqueId = message.getUniqueId();
        final String content = message.getContent();

        // This is GRPC response message
        if (StringUtils.isEmpty(message.getSeqNum()) && StringUtils.isEmpty(message.getUniqueId())) {
            return (T) JsonUtils.parseTypeReferenceObject(content,
                    new TypeReference<HashMap<String, String>>() {
                    });
        }

        if (EventMeshCommon.CLOUD_EVENTS_PROTOCOL_NAME.equals(protocolType)) {
            final String contentType = message.getPropertiesOrDefault(ProtocolKey.CONTENT_TYPE, JsonFormat.CONTENT_TYPE);
            final CloudEvent cloudEvent = Objects.requireNonNull(EventFormatProvider.getInstance().resolveFormat(contentType))
                    .deserialize(content.getBytes(StandardCharsets.UTF_8));

            final CloudEventBuilder cloudEventBuilder = CloudEventBuilder.from(cloudEvent)
                    .withSubject(message.getTopic())
                    .withExtension(ProtocolKey.SEQ_NUM, message.getSeqNum())
                    .withExtension(ProtocolKey.UNIQUE_ID, message.getUniqueId());

            message.getPropertiesMap().forEach(cloudEventBuilder::withExtension);

            return (T) cloudEventBuilder.build();
        } else {
            return (T) EventMeshMessage.builder()
                    .content(content)
                    .topic(message.getTopic())
                    .bizSeqNo(seq)
                    .uniqueId(uniqueId)
                    .prop(message.getPropertiesMap())
                    .build();
        }
    }

    public static <T> SimpleMessage buildSimpleMessage(final T message, final EventMeshGrpcClientConfig clientConfig,
                                                       final String protocolType) {
        if (EventMeshCommon.CLOUD_EVENTS_PROTOCOL_NAME.equals(protocolType)) {
            final CloudEvent cloudEvent = (CloudEvent) message;
            final String contentType = StringUtils.isEmpty(cloudEvent.getDataContentType())
                    ? Constants.CONTENT_TYPE_CLOUDEVENTS_JSON
                    : cloudEvent.getDataContentType();
            final byte[] bodyByte = Objects.requireNonNull(EventFormatProvider.getInstance().resolveFormat(contentType))
                    .serialize(cloudEvent);
            final String content = new String(bodyByte, StandardCharsets.UTF_8);
            final String ttl = cloudEvent.getExtension(Constants.EVENTMESH_MESSAGE_CONST_TTL) == null
                    ? Constants.DEFAULT_EVENTMESH_MESSAGE_TTL
                    : Objects.requireNonNull(cloudEvent.getExtension(Constants.EVENTMESH_MESSAGE_CONST_TTL)).toString();

            final String seqNum = cloudEvent.getExtension(ProtocolKey.SEQ_NUM) == null
                    ? RandomStringUtils.generateNum(30)
                    : Objects.requireNonNull(cloudEvent.getExtension(ProtocolKey.SEQ_NUM)).toString();

            final String uniqueId = cloudEvent.getExtension(ProtocolKey.UNIQUE_ID) == null
                    ? RandomStringUtils.generateNum(30)
                    : Objects.requireNonNull(cloudEvent.getExtension(ProtocolKey.UNIQUE_ID)).toString();

            final SimpleMessage.Builder builder = SimpleMessage.newBuilder()
                    .setHeader(EventMeshClientUtil.buildHeader(clientConfig, protocolType))
                    .setProducerGroup(clientConfig.getProducerGroup())
                    .setTopic(cloudEvent.getSubject())
                    .setTtl(ttl)
                    .setSeqNum(seqNum)
                    .setUniqueId(uniqueId)
                    .setContent(content)
                    .putProperties(ProtocolKey.CONTENT_TYPE, contentType);

            cloudEvent.getExtensionNames().forEach(extName -> {
                builder.putProperties(extName, Objects.requireNonNull(cloudEvent.getExtension(extName)).toString());
            });
            return builder.build();

        } else {
            final EventMeshMessage eventMeshMessage = (EventMeshMessage) message;

            final String ttl = eventMeshMessage.getProp(Constants.EVENTMESH_MESSAGE_CONST_TTL) == null
                    ? Constants.DEFAULT_EVENTMESH_MESSAGE_TTL
                    : eventMeshMessage.getProp(Constants.EVENTMESH_MESSAGE_CONST_TTL);
            final Map<String, String> props = eventMeshMessage.getProp() == null
                    ? new HashMap<>() : eventMeshMessage.getProp();

            final String seqNum = eventMeshMessage.getBizSeqNo() == null ? RandomStringUtils.generateNum(30)
                    : eventMeshMessage.getBizSeqNo();

            final String uniqueId = eventMeshMessage.getUniqueId() == null ? RandomStringUtils.generateNum(30)
                    : eventMeshMessage.getUniqueId();

            return SimpleMessage.newBuilder()
                    .setHeader(EventMeshClientUtil.buildHeader(clientConfig, protocolType))
                    .setProducerGroup(clientConfig.getProducerGroup())
                    .setTopic(eventMeshMessage.getTopic())
                    .setContent(eventMeshMessage.getContent())
                    .setSeqNum(seqNum)
                    .setUniqueId(uniqueId)
                    .setTtl(ttl)
                    .putAllProperties(props)
                    .build();
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> BatchMessage buildBatchMessages(final List<T> messageList,
                                                      final EventMeshGrpcClientConfig clientConfig,
                                                      final String protocolType) {
        if (EventMeshCommon.CLOUD_EVENTS_PROTOCOL_NAME.equals(protocolType)) {
            List<CloudEvent> events = (List<CloudEvent>) messageList;
            BatchMessage.Builder messageBuilder = BatchMessage.newBuilder()
                    .setHeader(EventMeshClientUtil.buildHeader(clientConfig, protocolType))
                    .setProducerGroup(clientConfig.getProducerGroup())
                    .setTopic(events.get(0).getSubject());

            events.forEach(event -> {
                final String contentType = StringUtils.isEmpty(event.getDataContentType())
                        ? Constants.CONTENT_TYPE_CLOUDEVENTS_JSON
                        : event.getDataContentType();

                final byte[] bodyByte = Objects.requireNonNull(EventFormatProvider.getInstance().resolveFormat(contentType))
                        .serialize(event);
                final String content = new String(bodyByte, StandardCharsets.UTF_8);

                final String ttl = event.getExtension(Constants.EVENTMESH_MESSAGE_CONST_TTL) == null
                        ? Constants.DEFAULT_EVENTMESH_MESSAGE_TTL
                        : Objects.requireNonNull(event.getExtension(Constants.EVENTMESH_MESSAGE_CONST_TTL)).toString();

                BatchMessage.MessageItem messageItem = BatchMessage.MessageItem.newBuilder()
                        .setContent(content)
                        .setTtl(ttl)
                        .setSeqNum(Objects.requireNonNull(event.getExtension(ProtocolKey.SEQ_NUM)).toString())
                        .setUniqueId(Objects.requireNonNull(event.getExtension(ProtocolKey.UNIQUE_ID)).toString())
                        .putProperties(ProtocolKey.CONTENT_TYPE, contentType)
                        .build();

                messageBuilder.addMessageItem(messageItem);
            });
            return messageBuilder.build();
        } else {
            final List<EventMeshMessage> eventMeshMessages = (List<EventMeshMessage>) messageList;
            final BatchMessage.Builder messageBuilder = BatchMessage.newBuilder()
                    .setHeader(EventMeshClientUtil.buildHeader(clientConfig, protocolType))
                    .setProducerGroup(clientConfig.getProducerGroup())
                    .setTopic(eventMeshMessages.get(0).getTopic());

            eventMeshMessages.forEach(message -> {
                BatchMessage.MessageItem item = BatchMessage.MessageItem.newBuilder()
                        .setContent(message.getContent())
                        .setUniqueId(message.getUniqueId())
                        .setSeqNum(message.getBizSeqNo())
                        .setTtl(Optional.ofNullable(message.getProp(Constants.EVENTMESH_MESSAGE_CONST_TTL))
                                .orElseGet(() -> Constants.DEFAULT_EVENTMESH_MESSAGE_TTL))
                        .putAllProperties(message.getProp())
                        .build();
                messageBuilder.addMessageItem(item);
            });

            return messageBuilder.build();
        }
    }
}
