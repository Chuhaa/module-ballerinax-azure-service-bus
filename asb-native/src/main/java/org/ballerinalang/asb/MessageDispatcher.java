/*
 * Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ballerinalang.asb;

import com.microsoft.azure.servicebus.*;
import io.ballerina.runtime.api.Runtime;
import io.ballerina.runtime.api.async.Callback;
import io.ballerina.runtime.api.async.StrandMetadata;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.AnnotatableType;
import io.ballerina.runtime.api.types.MethodType;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.utils.JsonUtils;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import org.ballerinalang.asb.ModuleUtils;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import static io.ballerina.runtime.api.constants.RuntimeConstants.ORG_NAME_SEPARATOR;
import static io.ballerina.runtime.api.constants.RuntimeConstants.VERSION_SEPARATOR;
import static org.ballerinalang.asb.ASBConstants.*;
import static org.ballerinalang.asb.connection.ConnectionUtils.toBMap;
import static org.ballerinalang.asb.connection.ListenerUtils.isServiceAttached;

/**
 * Handles and dispatched messages with data binding.
 */
public class MessageDispatcher {
    private static final Logger log = Logger.getLogger(MessageDispatcher.class.getName());

    private BObject service;
    private String queueName;
    private String connectionKey;
    private Runtime runtime;
    private IMessageReceiver receiver;

    /**
     * Initialize the Message Dispatcher.
     *
     * @param service Ballerina service instance.
     * @param runtime Ballerina runtime instance.
     * @param iMessageReceiver Asb MessageReceiver instance.
     */
    public MessageDispatcher(BObject service, Runtime runtime, IMessageReceiver iMessageReceiver) {
        this.service = service;
        this.queueName = getQueueNameFromConfig(service);
        this.connectionKey = getConnectionStringFromConfig(service);
        this.runtime = runtime;
        this.receiver = iMessageReceiver;
    }

    /**
     * Get queue name from annotation configuration attached to the service.
     *
     * @param service Ballerina service instance.
     * @return Queue name from annotation configuration attached to the service.
     */
    public static String getQueueNameFromConfig(BObject service) {
        BMap serviceConfig = (BMap) ((AnnotatableType) service.getType())
                .getAnnotation(StringUtils.fromString(ModuleUtils.getModule().getOrg() + ORG_NAME_SEPARATOR 
                + ModuleUtils.getModule().getName() + VERSION_SEPARATOR + ModuleUtils.getModule().getMajorVersion() 
                + ":" + ASBConstants.SERVICE_CONFIG));
        @SuppressWarnings(ASBConstants.UNCHECKED)
        BMap<BString, Object> queueConfig =
                (BMap) serviceConfig.getMapValue(ASBConstants.ALIAS_QUEUE_CONFIG);
        return queueConfig.getStringValue(ASBConstants.QUEUE_NAME).getValue();
    }

    /**
     * Get connection string from annotation configuration attached to the service.
     *
     * @param service Ballerina service instance.
     * @return Connection string from annotation configuration attached to the service.
     */
    public static String getConnectionStringFromConfig(BObject service) {
        BMap serviceConfig = (BMap) ((AnnotatableType) service.getType())
                .getAnnotation(StringUtils.fromString(ModuleUtils.getModule().getOrg() + ORG_NAME_SEPARATOR 
                + ModuleUtils.getModule().getName() + VERSION_SEPARATOR + ModuleUtils.getModule().getMajorVersion() 
                + ":" + ASBConstants.SERVICE_CONFIG));
        @SuppressWarnings(ASBConstants.UNCHECKED)
        BMap<BString, Object> queueConfig =
                (BMap) serviceConfig.getMapValue(ASBConstants.ALIAS_QUEUE_CONFIG);
        return queueConfig.getStringValue(CONNECTION_STRING).getValue();
    }

    /**
     * Get receive mode from annotation configuration attached to the service.
     *
     * @param service Ballerina service instance.
     * @return Connection string from annotation configuration attached to the service.
     */
    public static String getReceiveModeFromConfig(BObject service) {
        BMap serviceConfig = (BMap) ((AnnotatableType) service.getType())
                .getAnnotation(StringUtils.fromString(ModuleUtils.getModule().getOrg() + ORG_NAME_SEPARATOR 
                + ModuleUtils.getModule().getName() + VERSION_SEPARATOR + ModuleUtils.getModule().getMajorVersion() 
                + ":" + ASBConstants.SERVICE_CONFIG));
        @SuppressWarnings(ASBConstants.UNCHECKED)
        BMap<BString, Object> queueConfig =
                (BMap) serviceConfig.getMapValue(ASBConstants.ALIAS_QUEUE_CONFIG);
        if (queueConfig.getStringValue(RECEIVE_MODE) != null) {
            return queueConfig.getStringValue(RECEIVE_MODE).getValue();
        }
        return PEEKLOCK;
    }

    /**
     * Start receiving messages asynchronously and dispatch the messages to the attached service.
     *
     * @param listener Ballerina listener object.
     */
    public void receiveMessages(BObject listener) {
        log.info("Consumer service started for queue " + queueName);

        try {
            ExecutorService executorService = Executors.newSingleThreadExecutor();
            this.pumpMessage(receiver, executorService);
        } catch (IllegalArgumentException e) {
            ASBUtils.returnErrorValue(e.getMessage());
        }

        ArrayList<BObject> startedServices =
                (ArrayList<BObject>) listener.getNativeData(ASBConstants.STARTED_SERVICES);
        startedServices.add(service);
        service.addNativeData(ASBConstants.QUEUE_NAME.getValue(), queueName);
    }

    /**
     * Asynchronously pump messages from the Azure service bus.
     *
     * @param receiver Ballerina listener object.
     * @param executorService Thread executor for processing the messages.
     */
    public void pumpMessage(IMessageReceiver receiver, ExecutorService executorService) {
        if(isServiceAttached()) {
            CompletableFuture<IMessage> receiveMessageFuture = receiver.receiveAsync();

            receiveMessageFuture.handleAsync((message, receiveEx) -> {
                if (receiveEx != null) {
                    log.info("Receiving message from entity failed.");
                    pumpMessage(receiver, executorService);
                } else if (message == null) {
                    log.info("Receive from entity returned no messages.");
                    pumpMessage(receiver, executorService);
                } else {
                    handleDispatch(message);
//                    try {
//                        receiver.complete(message.getLockToken());
//                    } catch (Exception e) {
//                        return ASBUtils.returnErrorValue(e.getMessage());
//                    }
                    pumpMessage(receiver, executorService);
                    return null;
                }
                return null;
            }, executorService);
        }
    }

    /**
     * Handle the dispatch of message to the service.
     *
     * @param message Received azure service bus message instance.
     */
    private void handleDispatch(IMessage message) {
        MethodType[] attachedFunctions = service.getType().getMethods();
        MethodType onMessageFunction;
        if (FUNC_ON_MESSAGE.equals(attachedFunctions[0].getName())) {
            onMessageFunction = attachedFunctions[0];
        } else {
            return;
        }
        Type[] paramTypes = onMessageFunction.getParameterTypes();
        int paramSize = paramTypes.length;
        dispatchMessage(message);
    }

    /**
     * Dispatch message to the service.
     *
     * @param message Received azure service bus message instance.
     */
    private void dispatchMessage(IMessage message) {
        try {
            Callback callback = new ASBResourceCallback();
            BMap<BString, Object> messageBObject = getMessageRecord(message);
            executeResourceOnMessage(callback, messageBObject, true);
        } catch (BError exception) {
            ASBUtils.returnErrorValue("Error occur while dispatching the message to the service " +
                    exception.getMessage());
            handleError(message);
        }
    }

    /**
     * Handle error when dispatching message to the service.
     *
     * @param message Received azure service bus message instance.
     */
    private void handleError(IMessage message) {
        BError error = ASBUtils.returnErrorValue(ASBConstants.DISPATCH_ERROR);
        BMap<BString, Object> messageBObject = getMessageRecord(message);
        try {
            Callback callback = new ASBResourceCallback();
            executeResourceOnError(callback, messageBObject, true, error, true);
        } catch (BError exception) {
            throw ASBUtils.returnErrorValue("Error occurred in ASB service. ");
        }
    }

    /**
     * Get the ballerina Message record from the azure service bus message object.
     *
     * @param message Received azure service bus message instance.
     */
    private BMap<BString, Object> getMessageRecord(IMessage message)  {
        Object[] values = new Object[14];
        values[0] = ValueCreator.createArrayValue(message.getBody());
        values[1] = StringUtils.fromString(message.getContentType());
        values[2] = StringUtils.fromString(message.getMessageId());
        values[3] = StringUtils.fromString(message.getTo());
        values[4] = StringUtils.fromString(message.getReplyTo());
        values[5] = StringUtils.fromString(message.getReplyToSessionId());
        values[6] = StringUtils.fromString(message.getLabel());
        values[7] = StringUtils.fromString(message.getSessionId());
        values[8] = StringUtils.fromString(message.getCorrelationId());
        values[9] = StringUtils.fromString(message.getPartitionKey());
        values[10] = message.getTimeToLive().getSeconds();
        values[11] = message.getSequenceNumber();
        values[12] = StringUtils.fromString(message.getLockToken().toString());
        BMap<BString, Object> applicationProperties =
                ValueCreator.createRecordValue(ModuleUtils.getModule(), APPLICATION_PROPERTIES);
        Object[] propValues = new Object[1];
        propValues[0] = toBMap(message.getProperties());
        values[13] = ValueCreator.createRecordValue(applicationProperties, propValues);
        BMap<BString, Object> messageRecord =
                ValueCreator.createRecordValue(ModuleUtils.getModule(), MESSAGE_RECORD);
        return ValueCreator.createRecordValue(messageRecord, values);
    }

    private void executeResourceOnMessage(Callback callback, Object... args) {
        StrandMetadata metaData = new StrandMetadata(ModuleUtils.getModule().getOrg(), 
                ModuleUtils.getModule().getName(), ModuleUtils.getModule().getMajorVersion(), FUNC_ON_MESSAGE);
        executeResource(ASBConstants.FUNC_ON_MESSAGE, callback, metaData, args);
    }

    private void executeResourceOnError(Callback callback, Object... args) {
        StrandMetadata metaData = new StrandMetadata(ModuleUtils.getModule().getOrg(), 
                ModuleUtils.getModule().getName(), ModuleUtils.getModule().getMajorVersion(), FUNC_ON_ERROR);
        executeResource(ASBConstants.FUNC_ON_ERROR, callback, metaData, args);
    }

    private void executeResource(String function, Callback callback, StrandMetadata metaData,
                                 Object... args) {
        runtime.invokeMethodAsync(service, function, null, metaData, callback, args);
    }
}
