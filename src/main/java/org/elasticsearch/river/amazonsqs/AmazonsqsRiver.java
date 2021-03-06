/*
* Licensed to Elastic Search and Shay Banon under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership. Elastic Search licenses this
* file to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.elasticsearch.river.amazonsqs;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;

import com.amazonaws.auth.BasicAWSCredentials;

import com.amazonaws.services.sqs.AmazonSQSAsyncClient;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;

import java.io.IOException;
import java.util.ArrayList;

import org.elasticsearch.client.Client;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.river.AbstractRiverComponent;
import org.elasticsearch.river.River;
import org.elasticsearch.river.RiverName;
import org.elasticsearch.river.RiverSettings;
import java.util.List;
import java.util.Map;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;


/**
* @author aleski
*/
public class AmazonsqsRiver extends AbstractRiverComponent implements River {

  private final Client client;
  private final AmazonSQSAsyncClient sqs;
  private final ObjectMapper mapper;

  private final String INDEX;
  private final String ACCESSKEY;
  private final String SECRETKEY;
  private final String QUEUE_URL;
  private final String REGION;
  private final int MAX_MESSAGES;
  private final int TIMEOUT;

  private volatile boolean closed = false;
  private volatile Thread thread;

  @SuppressWarnings({"unchecked"})

  @Inject
  public AmazonsqsRiver(RiverName riverName, RiverSettings settings, Client client) {
    super(riverName, settings);
    this.client = client;

    if ( settings.settings().containsKey("amazonsqs") ) {
      Map<String, Object> sqsSettings = (Map<String, Object>) settings.settings().get("amazonsqs");

      REGION = XContentMapValues.nodeStringValue(sqsSettings.get("region"), "null");
      ACCESSKEY = XContentMapValues.nodeStringValue(sqsSettings.get("accesskey"), "null");
      SECRETKEY = XContentMapValues.nodeStringValue(sqsSettings.get("secretkey"), "null");
      QUEUE_URL = XContentMapValues.nodeStringValue(sqsSettings.get("queue_url"), "null");

    } else {
      REGION = settings.globalSettings().get("cloud.aws.region");
      ACCESSKEY = settings.globalSettings().get("cloud.aws.access_key");
      SECRETKEY = settings.globalSettings().get("cloud.aws.secret_key");
      QUEUE_URL = settings.globalSettings().get("cloud.aws.sqs.queue_url");

    }

    if ( settings.settings().containsKey("index") ) {
      Map<String, Object> indexSettings = (Map<String, Object>) settings.settings().get("index");
      INDEX = XContentMapValues.nodeStringValue(indexSettings.get("index"), "elasticsearch");
      MAX_MESSAGES = XContentMapValues.nodeIntegerValue(indexSettings.get("max_messages"), 10);
      TIMEOUT = XContentMapValues.nodeIntegerValue(indexSettings.get("timeout_seconds"), 10);
    } else {
      INDEX = settings.globalSettings().get("cluster.name");
      MAX_MESSAGES = 10;
      TIMEOUT = 10;
    }

    sqs = new AmazonSQSAsyncClient(new BasicAWSCredentials(ACCESSKEY, SECRETKEY));
    sqs.setEndpoint("https://".concat(REGION).concat(".queue.amazonaws.com"));
    mapper = new ObjectMapper();
  }

  public void start() {
    logger.info("creating amazonsqs river using queue {}", QUEUE_URL);

    thread = EsExecutors.daemonThreadFactory(settings.globalSettings(), "amazonsqs_river").newThread(new Consumer());
    thread.start();
  }

  public void close() {

    if (closed) {
      return;
    }

    logger.info("closing amazonsqs river");
    closed = true;
    thread.interrupt();
  }

  private class Consumer implements Runnable {

    private int idleCount = 0;

    public void run() {
      String id = null;	// document id
      String type = null;	// document type
      String indexName = null; // document index
      Map<String, Object> data = null; // document data for indexing

      while (!closed) {
        // pull messages from SQS
        List<JsonNode> msgs = pullMessages();
        int sleeptime = TIMEOUT * 1000;

        try {
          BulkRequestBuilder bulkRequestBuilder = client.prepareBulk();

          for (JsonNode msg : msgs) {
            if ( msg.has("_id") && msg.has("_type") ) {

              id = msg.get("_id").getTextValue();
              type = msg.get("_type").getTextValue();

              //Support for dynamic indexes
              if ( msg.has("_index") ) {
                indexName = msg.get("_index").getTextValue();
              } else {
                indexName = INDEX;
              }

              if ( msg.has("_data") ){
                data = mapper.readValue(msg.get("_data"), new TypeReference<Map<String, Object>>(){});
                bulkRequestBuilder.add(client.prepareIndex(indexName, type, id).setSource(data).request());
              } else {
                bulkRequestBuilder.add(client.prepareDelete(indexName, type, id).request());
              }
            }
          }

          // sleep less when there are lots of messages in queue
          // sleep more when idle
          if(bulkRequestBuilder.numberOfActions() > 0) {

            BulkResponse response = bulkRequestBuilder.execute().actionGet();
            if (response.hasFailures()) {
              logger.warn("Bulk operation completed with errors: " +
              response.buildFailureMessage());
            }

            // many tasks in queue => throttle up
            if (bulkRequestBuilder.numberOfActions() >= (MAX_MESSAGES / 2)) {
              sleeptime = 1000;
            } else if (bulkRequestBuilder.numberOfActions() == MAX_MESSAGES) {
              sleeptime = 100;
            }

            idleCount = 0;

          } else {
            idleCount++;
            // no tasks in queue => throttle down
            if( idleCount >= 3 ) {
              sleeptime *= 10;
            }
          }
        } catch (Exception e) {
          logger.error("Bulk index operation failed {}", e);
          continue;
        }

        try {
          Thread.sleep(sleeptime);
        } catch (InterruptedException e) {
          if (closed) {
            break;
          }
        }
      }
    }

    private List<JsonNode> pullMessages() {
      List<JsonNode> msgs = new ArrayList<JsonNode> ();

      if(!isBlank(QUEUE_URL)){

      try {
        ReceiveMessageRequest receiveReq = new ReceiveMessageRequest(QUEUE_URL);
        receiveReq.setMaxNumberOfMessages(MAX_MESSAGES);
        List<Message> list = sqs.receiveMessage(receiveReq).getMessages();

        if (list != null && !list.isEmpty()) {
          for (Message message : list) {

            if(!isBlank(message.getBody())) {
              msgs.add(mapper.readTree(message.getBody()));
            }

            sqs.deleteMessage(new DeleteMessageRequest(QUEUE_URL, message.getReceiptHandle()));
          }
        }

      } catch (IOException ex) {
        logger.error(ex.getMessage());
      } catch (AmazonServiceException ase) {
        logException(ase);
      } catch (AmazonClientException ace) {
        logger.error("Could not reach SQS. {}", ace.getMessage());
      }
    }

    return msgs;
  }

  private void logException(AmazonServiceException ase) {
    logger.error("AmazonServiceException: error={}, statuscode={}, " + "awserrcode={}, errtype={}, reqid={}",
      new Object[]{ ase.getMessage(), ase.getStatusCode(), ase.getErrorCode(), ase.getErrorType(), ase.getRequestId()});
    }
  }

  private boolean isBlank(String str) {
    return str == null || str.isEmpty() || str.trim().isEmpty();
  }

}
