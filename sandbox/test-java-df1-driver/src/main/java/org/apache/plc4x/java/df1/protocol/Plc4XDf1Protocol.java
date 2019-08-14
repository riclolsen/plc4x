/*
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 */
package org.apache.plc4x.java.df1.protocol;

import com.github.jinahya.bit.io.DefaultBitInput;
import com.github.jinahya.bit.io.DefaultBitOutput;
import io.netty.channel.ChannelHandlerContext;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.apache.plc4x.java.api.messages.PlcRequest;
import org.apache.plc4x.java.api.messages.PlcResponse;
import org.apache.plc4x.java.api.messages.PlcWriteRequest;
import org.apache.plc4x.java.api.model.PlcField;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.base.PlcMessageToMessageCodec;
import org.apache.plc4x.java.base.messages.DefaultPlcReadResponse;
import org.apache.plc4x.java.base.messages.InternalPlcReadRequest;
import org.apache.plc4x.java.base.messages.PlcRequestContainer;
import org.apache.plc4x.java.base.messages.items.BaseDefaultFieldItem;
import org.apache.plc4x.java.base.messages.items.DefaultFloatFieldItem;
import org.apache.plc4x.java.base.messages.items.DefaultIntegerFieldItem;
import org.apache.plc4x.java.df1.DF1Command;
import org.apache.plc4x.java.df1.DF1UnprotectedReadRequest;
import org.apache.plc4x.java.df1.DF1UnprotectedReadResponse;
import org.apache.plc4x.java.df1.Df1Field;
import org.apache.plc4x.plugins.codegenerator.language.mspec.model.fields.DefaultArrayField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class Plc4XDf1Protocol extends PlcMessageToMessageCodec<DF1Command, PlcRequestContainer> {

    private static final Logger logger = LoggerFactory.getLogger(Plc4XDf1Protocol.class);

    private final AtomicInteger transactionId = new AtomicInteger(0);
    private Map<Integer, PlcRequestContainer> requests = new ConcurrentHashMap<>();

    @Override
    protected void encode(ChannelHandlerContext ctx, PlcRequestContainer msg, List<Object> out) throws Exception {
        logger.trace("Received Request {} to send out", msg);
        if (msg.getRequest() instanceof PlcReadRequest) {
            for (PlcField field : ((PlcReadRequest) msg.getRequest()).getFields()) {
                if (!(field instanceof Df1Field)) {
                    throw new IllegalArgumentException("Invalid field type found inside Df1 Request");
                }
                int address = ((Df1Field) field).getAddress();
                short size = ((Df1Field) field).getDataType().getLength();
                int transactionId = this.transactionId.getAndIncrement();
                logger.debug("Creating request for offset {}, with length {} and transaction id {}", address, size, transactionId);
                switch (((Df1Field) field).getAddress_type()) {
                    case OFFSET:
                        out.add(new DF1UnprotectedReadRequest((short) 0x00, transactionId, address, size));
                        break;
                    case LOGICAL:
                        // TODO: add 'protected typed logical read ' to mspec and case
                        throw new NotImplementedException("not yet implemented in mspec");
                }

            }
        } else {
            throw new IllegalStateException("This should not happen!");
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DF1Command msg, List<Object> out) throws Exception {
        logger.trace("Received DF1 Command incoming {}", msg);
        int transactionId = msg.getTransactionCounter();
        if (!requests.containsKey(transactionId)) {
            logger.warn("Received a response to unknown transaction id {}", transactionId);
            ctx.fireExceptionCaught(new RuntimeException("Received a response to unknown transaction id"));
            ctx.close();
            return;
        }
        // As every response has a matching request, get this request based on the tpdu.
        PlcRequestContainer requestContainer = requests.remove(transactionId);
        PlcRequest request = requestContainer.getRequest();

        // Handle the response.
        PlcResponse response = null;
        if (request instanceof PlcReadRequest) {
            /*
            Things to do
            - check response code (if there is something like that?
            - cast the bytes to right datatype
            - create Response
             */
            // We can do this as we have only one fieldName in DF1
            final String fieldName = ((PlcReadRequest) request).getFieldNames().iterator().next();
            // TODO can there be another code than ok?
            final PlcResponseCode responseCode = PlcResponseCode.OK;
            // TODO maybe check for different status bytes
            final Df1Field field = (Df1Field) ((PlcReadRequest) request).getField(fieldName);
            // Cast byte and create response item
            BaseDefaultFieldItem responseItem = null;
            short[] data = ((DF1UnprotectedReadResponse)msg).getData();
            switch (field.getDataType()) {
                case BIT:
                    break;
                case INTEGER:
                    // TODO cast the bytes to Integer
                    responseItem = new DefaultIntegerFieldItem(-1);
                    break;
                case FLOAT:
                    break;
                case BIT_STRING:
                    break;
                case ARRAY:
                    break;
                // TODO add all other cases here...
                default:
                    throw new NotImplementedException("The DataType " + field.getDataType() + " is currently not implemented!");
            }
            response = new DefaultPlcReadResponse(((InternalPlcReadRequest) request),
                Collections.singletonMap(fieldName,
                    Pair.of(responseCode, responseItem)));
        } else if (request instanceof PlcWriteRequest) {
            logger.warn("Writing is currently not implemented but received a write response?!");
            ctx.close();
            throw new NotImplementedException("This is currently not implemented!");
        }

        // Confirm the response being handled.
        if (response != null) {
            requestContainer.getResponseFuture().complete(response);
        }
    }


}